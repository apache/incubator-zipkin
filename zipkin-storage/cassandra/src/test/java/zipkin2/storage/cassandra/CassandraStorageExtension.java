/*
 * Copyright 2015-2020 The OpenZipkin Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package zipkin2.storage.cassandra;

import com.codahale.metrics.Gauge;
import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.metadata.Node;
import com.datastax.oss.driver.api.core.metrics.DefaultNodeMetric;
import com.datastax.oss.driver.api.core.metrics.Metrics;
import com.datastax.oss.driver.api.core.servererrors.InvalidQueryException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.rnorth.ducttape.unreliables.Unreliables;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.ContainerLaunchException;
import org.testcontainers.containers.GenericContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static zipkin2.Call.propagateIfFatal;
import static zipkin2.storage.cassandra.ITCassandraStorage.SEARCH_TABLES;
import static zipkin2.storage.cassandra.Schema.TABLE_DEPENDENCY;
import static zipkin2.storage.cassandra.Schema.TABLE_SPAN;

public class CassandraStorageExtension implements BeforeAllCallback, AfterAllCallback {
  static final Logger LOGGER = LoggerFactory.getLogger(CassandraStorageExtension.class);
  static final int CASSANDRA_PORT = 9042;
  final String image;
  CassandraContainer container;
  CqlSession globalSession;

  CassandraStorageExtension(String image) {
    this.image = image;
  }

  @Override public void beforeAll(ExtensionContext context) {
    if (context.getRequiredTestClass().getEnclosingClass() != null) {
      // Only run once in outermost scope.
      return;
    }

    if (!"true".equals(System.getProperty("docker.skip"))) {
      try {
        LOGGER.info("Starting docker image " + image);
        container = new CassandraContainer(image).withExposedPorts(CASSANDRA_PORT);
        container.start();
      } catch (RuntimeException e) {
        LOGGER.warn("Couldn't start docker image " + image + ": " + e.getMessage(), e);
      }
    } else {
      LOGGER.info("Skipping startup of docker " + image);
    }

    try {
      globalSession = tryToInitializeSession(contactPoint());
    } catch (RuntimeException | Error e) {
      if (container == null) throw e;
      LOGGER.warn("Couldn't connect to docker image " + image + ": " + e.getMessage(), e);
      container.stop();
      container = null; // try with local connection instead
      globalSession = tryToInitializeSession(contactPoint());
    }
    LOGGER.info("Using contactPoint " + contactPoint());
  }

  // Builds a session without trying to use a namespace or init UDTs
  static CqlSession tryToInitializeSession(String contactPoint) {
    CassandraStorage storage = newStorageBuilder(contactPoint).build();
    CqlSession session = null;
    try {
      session = DefaultSessionFactory.buildSession(storage);
      session.execute("SELECT now() FROM system.local");
    } catch (Throwable e) {
      propagateIfFatal(e);
      if (session != null) session.close();
      assumeTrue(false, e.getMessage());
    }
    return session;
  }

  CassandraStorage.Builder newStorageBuilder() {
    return newStorageBuilder(contactPoint());
  }

  static CassandraStorage.Builder newStorageBuilder(String contactPoint) {
    return CassandraStorage.newBuilder().contactPoints(contactPoint).maxConnections(1);
  }

  String contactPoint() {
    if (container != null && container.isRunning()) {
      return container.getContainerIpAddress() + ":" + container.getMappedPort(CASSANDRA_PORT);
    } else {
      return "127.0.0.1:" + CASSANDRA_PORT;
    }
  }

  void clear(CassandraStorage storage) {
    // Clear any key cache
    CassandraSpanConsumer spanConsumer = storage.spanConsumer;
    if (spanConsumer != null) spanConsumer.clear();

    CqlSession session = storage.session.session;
    if (session == null) session = globalSession;

    List<String> toTruncate = new ArrayList<>(SEARCH_TABLES);
    toTruncate.add(TABLE_DEPENDENCY);
    toTruncate.add(TABLE_SPAN);

    for (String table : toTruncate) {
      try {
        session.execute("TRUNCATE " + storage.keyspace + "." + table);
      } catch (InvalidQueryException e) {
        assertThat(e).hasMessage("unconfigured table " + table);
      }
    }

    blockWhileInFlight(storage);
  }

  @Override public void afterAll(ExtensionContext context) {
    if (context.getRequiredTestClass().getEnclosingClass() != null) {
      // Only run once in outermost scope.
      return;
    }
    if (globalSession != null) globalSession.close();
  }

  static final class CassandraContainer extends GenericContainer<CassandraContainer> {
    CassandraContainer(String image) {
      super(image);
    }

    @Override protected void waitUntilContainerStarted() {
      Unreliables.retryUntilSuccess(120, TimeUnit.SECONDS, () -> {
        if (!isRunning()) throw new ContainerLaunchException("Container failed to start");

        String contactPoint = getContainerIpAddress() + ":" + getMappedPort(9042);
        try (CqlSession session = tryToInitializeSession(contactPoint)) {
          session.execute("SELECT now() FROM system.local");
          logger().info("Obtained a connection to container ({})", contactPoint);
          return null; // unused value
        }
      });
    }
  }

  static void blockWhileInFlight(CassandraStorage storage) {
    CqlSession session = storage.session.get();
    // Now, block until writes complete, notably so we can read them.
    boolean wasInFlight = false;
    while (true) {
      if (!poolInFlight(session)) {
        if (wasInFlight) sleep(100); // give a little more to avoid flakey tests
        return;
      }
      wasInFlight = true;
      sleep(100);
    }
  }

  static void sleep(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AssertionError(e);
    }
  }

  // Use metrics to wait for in-flight requests to settle per
  // https://groups.google.com/a/lists.datastax.com/g/java-driver-user/c/5um_yGNynow/m/cInH5I5jBgAJ
  static boolean poolInFlight(CqlSession session) {
    Collection<Node> nodes = session.getMetadata().getNodes().values();
    Optional<Metrics> metrics = session.getMetrics();
    for (Node node : nodes) {
      int inFlight = metrics.flatMap(m -> m.getNodeMetric(node, DefaultNodeMetric.IN_FLIGHT))
        .map(m -> ((Gauge<Integer>) m).getValue())
        .orElse(0);
      if (inFlight > 0) return true;
    }
    return false;
  }
}
