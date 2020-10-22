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
package zipkin2.storage.cassandra.internal;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.CqlSessionBuilder;
import com.datastax.oss.driver.api.core.auth.AuthProvider;
import com.datastax.oss.driver.api.core.config.DriverConfigLoader;
import com.datastax.oss.driver.api.core.config.DriverOption;
import com.datastax.oss.driver.api.core.config.ProgrammaticDriverConfigLoaderBuilder;
import com.datastax.oss.driver.internal.core.ssl.DefaultSslEngineFactory;
import com.datastax.oss.driver.internal.core.tracker.RequestLogger;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zipkin2.internal.Nullable;

import static com.datastax.oss.driver.api.core.config.DefaultDriverOption.REQUEST_CONSISTENCY;
import static com.datastax.oss.driver.api.core.config.DefaultDriverOption.REQUEST_DEFAULT_IDEMPOTENCE;
import static com.datastax.oss.driver.api.core.config.DefaultDriverOption.REQUEST_LOGGER_SUCCESS_ENABLED;
import static com.datastax.oss.driver.api.core.config.DefaultDriverOption.REQUEST_LOGGER_VALUES;
import static com.datastax.oss.driver.api.core.config.DefaultDriverOption.REQUEST_TIMEOUT;
import static com.datastax.oss.driver.api.core.config.DefaultDriverOption.REQUEST_TRACKER_CLASS;
import static com.datastax.oss.driver.api.core.config.DefaultDriverOption.REQUEST_WARN_IF_SET_KEYSPACE;
import static com.datastax.oss.driver.api.core.config.DefaultDriverOption.SSL_ENGINE_FACTORY_CLASS;

public final class SessionBuilder {
  /** Returns a connected session. Closes the cluster if any exception occurred. */
  public static CqlSession buildSession(
    String contactPoints,
    String localDc,
    Map<DriverOption, Integer> poolingOptions,
    @Nullable AuthProvider authProvider,
    boolean useSsl
  ) {
    // Some options aren't supported by builder methods. In these cases, we use driver config
    // See https://groups.google.com/a/lists.datastax.com/forum/#!topic/java-driver-user/Z8HrCDX47Q0
    ProgrammaticDriverConfigLoaderBuilder config =
      // We aren't reading any resources from the classpath, but this prevents errors running in the
      // server, where Thread.currentThread().getContextClassLoader() returns null
      DriverConfigLoader.programmaticBuilder(SessionBuilder.class.getClassLoader());

    // Ported from java-driver v3 PoolingOptions.setPoolTimeoutMillis as request timeout includes that
    config.withDuration(REQUEST_TIMEOUT, Duration.ofMinutes(1));

    CqlSessionBuilder builder = CqlSession.builder();
    builder.addContactPoints(parseContactPoints(contactPoints));
    if (authProvider != null) builder.withAuthProvider(authProvider);

    // In java-driver v3, we used LatencyAwarePolicy(DCAwareRoundRobinPolicy|RoundRobinPolicy)
    //   where DCAwareRoundRobinPolicy was used if localDc != null
    //
    // In java-driver v4, the default policy is token-aware and localDc is required. Hence, we
    // use the default load balancing policy
    //  * https://github.com/datastax/java-driver/blob/master/manual/core/load_balancing/README.md
    builder.withLocalDatacenter(localDc);
    config = config.withString(REQUEST_CONSISTENCY, "LOCAL_ONE");
    // Pooling options changed dramatically from v3->v4. This is a close match.
    poolingOptions.forEach(config::withInt);

    // All Zipkin CQL writes are idempotent
    config = config.withBoolean(REQUEST_DEFAULT_IDEMPOTENCE, true);

    if (useSsl) config = config.withClass(SSL_ENGINE_FACTORY_CLASS, DefaultSslEngineFactory.class);

    // Log categories can enable query logging
    Logger requestLogger = LoggerFactory.getLogger(RequestLogger.class);
    if (requestLogger.isDebugEnabled()) {
      config = config.withClass(REQUEST_TRACKER_CLASS, RequestLogger.class);
      config = config.withBoolean(REQUEST_LOGGER_SUCCESS_ENABLED, true);
      // Only show bodies when TRACE is enabled
      config = config.withBoolean(REQUEST_LOGGER_VALUES, requestLogger.isTraceEnabled());
    }

    // Don't warn: ensureSchema creates the keyspace. Hence, we need to "use" it later.
    config = config.withBoolean(REQUEST_WARN_IF_SET_KEYSPACE, false);

    return builder.withConfigLoader(config.build()).build();
  }

  static List<InetSocketAddress> parseContactPoints(String contactPoints) {
    List<InetSocketAddress> result = new ArrayList<>();
    for (String contactPoint : contactPoints.split(",", 100)) {
      HostAndPort parsed = HostAndPort.fromString(contactPoint, 9042);
      result.add(new InetSocketAddress(parsed.getHost(), parsed.getPort()));
    }
    return result;
  }
}
