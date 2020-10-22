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
package zipkin2.elasticsearch.integration;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.client.WebClientBuilder;
import com.linecorp.armeria.client.logging.LoggingClient;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.logging.LogLevel;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.containers.wait.strategy.HttpWaitStrategy;
import zipkin2.CheckResult;
import zipkin2.elasticsearch.ElasticsearchStorage;
import zipkin2.elasticsearch.ElasticsearchStorage.Builder;

import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static zipkin2.elasticsearch.integration.IgnoredDeprecationWarnings.IGNORE_THESE_WARNINGS;

class ElasticsearchStorageExtension implements BeforeAllCallback, AfterAllCallback {
  static final Logger LOGGER = LoggerFactory.getLogger(ElasticsearchStorageExtension.class);

  static final int ELASTICSEARCH_PORT = 9200;
  final String image;
  final Integer priority;
  GenericContainer<?> container;

  ElasticsearchStorageExtension(String image, Integer priority) {
    this.image = image;

    // This is so that both legacy and composable templates can be tested with this class
    this.priority = priority;
  }

  @Override public void beforeAll(ExtensionContext context) {
    if (context.getRequiredTestClass().getEnclosingClass() != null) {
      // Only run once in outermost scope.
      return;
    }

    if (!"true".equals(System.getProperty("docker.skip"))) {
      try {
        LOGGER.info("Starting docker image " + image);
        container =
          new GenericContainer<>(image)
            .withExposedPorts(ELASTICSEARCH_PORT)
            .waitingFor(new HttpWaitStrategy().forPath("/"));
        container.start();
        if (Boolean.parseBoolean(System.getenv("ES_DEBUG"))) {
          container.followOutput(new Slf4jLogConsumer(LoggerFactory.getLogger(image)));
        }
        LOGGER.info("Starting docker image " + image);
      } catch (RuntimeException e) {
        LOGGER.warn("Couldn't start docker image " + image + ": " + e.getMessage(), e);
      }
    } else {
      LOGGER.info("Skipping startup of docker " + image);
    }

    try {
      tryToInitializeSession();
    } catch (RuntimeException | Error e) {
      if (container == null) throw e;
      LOGGER.warn("Couldn't connect to docker image " + image + ": " + e.getMessage(), e);
      container.stop();
      container = null; // try with local connection instead
      tryToInitializeSession();
    }
  }

  @Override public void afterAll(ExtensionContext context) {
    if (context.getRequiredTestClass().getEnclosingClass() != null) {
      // Only run once in outermost scope.
      return;
    }

    if (container != null) {
      LOGGER.info("Stopping docker image " + image);
      container.stop();
    }
  }

  void tryToInitializeSession() {
    try (ElasticsearchStorage result = computeStorageBuilder().build()) {
      CheckResult check = result.check();
      assumeTrue(check.ok(), () -> "Could not connect to storage, skipping test: "
        + check.error().getMessage());
    }
  }

  Builder computeStorageBuilder() {
    WebClientBuilder builder = WebClient.builder(baseUrl())
      // Elasticsearch 7 never returns a response when receiving an HTTP/2 preface instead of the
      // more valid behavior of returning a bad request response, so we can't use the preface.
      //
      // TODO: find or raise a bug with Elastic
      .factory(ClientFactory.builder().useHttp2Preface(false).build());

    if (Boolean.parseBoolean(System.getenv("ES_DEBUG"))) {
      builder.decorator(c -> LoggingClient.builder()
        .requestLogLevel(LogLevel.INFO)
        .successfulResponseLogLevel(LogLevel.INFO).build(c));
    }
    builder.decorator((delegate, ctx, req) -> {
      final HttpResponse response = delegate.execute(ctx, req);
      return HttpResponse.from(response.aggregate().thenApply(r -> {
        // ES will return a 'warning' response header when using deprecated api, detect this and
        // fail early so we can do something about it.
        // Example usage: https://github.com/elastic/elasticsearch/blob/3049e55f093487bb582a7e49ad624961415ba31c/x-pack/plugin/security/src/internalClusterTest/java/org/elasticsearch/integration/IndexPrivilegeIntegTests.java#L559
        final String warningHeader = r.headers().get("warning");
        if (warningHeader != null) {
          if (IGNORE_THESE_WARNINGS.stream().noneMatch(p -> p.matcher(warningHeader).find())) {
            throw new IllegalArgumentException("Detected usage of deprecated API for request "
              + req.toString() + ":\n" + warningHeader);
          }
        }
        // Convert AggregatedHttpResponse back to HttpResponse.
        return r.toHttpResponse();
      }));
    });
    WebClient client = builder.build();
    return ElasticsearchStorage.newBuilder(() -> client)
      .index("zipkin-test")
      .flushOnWrites(true)
      .templatePriority(priority);
  }

  String baseUrl() {
    if (container != null && container.isRunning()) {
      return String.format(
        "http://%s:%d",
        container.getContainerIpAddress(), container.getMappedPort(ELASTICSEARCH_PORT));
    } else {
      // Use localhost if we failed to start a container (i.e. Docker is not available)
      return "http://localhost:" + ELASTICSEARCH_PORT;
    }
  }

  static String index(TestInfo testInfo) {
    String result;
    if (testInfo.getTestMethod().isPresent()) {
      result = testInfo.getTestMethod().get().getName();
    } else {
      assert testInfo.getTestClass().isPresent();
      result = testInfo.getTestClass().get().getSimpleName();
    }
    result = result.toLowerCase();
    return result.length() <= 48 ? result : result.substring(result.length() - 48);
  }
}
