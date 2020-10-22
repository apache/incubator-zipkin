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
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.RequestHeaders;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zipkin2.Span;
import zipkin2.elasticsearch.ElasticsearchStorage;
import zipkin2.elasticsearch.InternalForTests;
import zipkin2.storage.StorageComponent;

import static zipkin2.elasticsearch.integration.ElasticsearchStorageExtension.index;
import static zipkin2.storage.ITDependencies.aggregateLinks;

abstract class ITElasticsearchStorage {

  static final Logger LOGGER = LoggerFactory.getLogger(ITElasticsearchStorage.class);

  abstract ElasticsearchStorageExtension backend();

  @Nested
  class ITTraces extends zipkin2.storage.ITTraces<ElasticsearchStorage> {
    @Override protected StorageComponent.Builder newStorageBuilder(TestInfo testInfo) {
      return backend().computeStorageBuilder().index(index(testInfo));
    }

    @Override public void clear() throws IOException {
      storage.clear();
    }
  }

  @Nested
  class ITSpanStore extends zipkin2.storage.ITSpanStore<ElasticsearchStorage> {
    @Override protected StorageComponent.Builder newStorageBuilder(TestInfo testInfo) {
      return backend().computeStorageBuilder().index(index(testInfo));
    }

    @Override public void clear() throws IOException {
      storage.clear();
    }
  }

  @Nested
  class ITSpanStoreHeavy extends zipkin2.storage.ITSpanStoreHeavy<ElasticsearchStorage> {
    @Override protected StorageComponent.Builder newStorageBuilder(TestInfo testInfo) {
      return backend().computeStorageBuilder().index(index(testInfo));
    }

    @Override public void clear() throws IOException {
      storage.clear();
    }
  }

  @Nested
  class ITSearchEnabledFalse extends zipkin2.storage.ITSearchEnabledFalse<ElasticsearchStorage> {
    @Override protected StorageComponent.Builder newStorageBuilder(TestInfo testInfo) {
      return backend().computeStorageBuilder().index(index(testInfo));
    }

    @Override public void clear() throws IOException {
      storage.clear();
    }
  }

  @Nested
  class ITServiceAndSpanNames extends zipkin2.storage.ITServiceAndSpanNames<ElasticsearchStorage> {
    @Override protected StorageComponent.Builder newStorageBuilder(TestInfo testInfo) {
      return backend().computeStorageBuilder().index(index(testInfo));
    }

    @Override public void clear() throws IOException {
      storage.clear();
    }
  }

  @Nested
  class ITAutocompleteTags extends zipkin2.storage.ITAutocompleteTags<ElasticsearchStorage> {
    @Override protected StorageComponent.Builder newStorageBuilder(TestInfo testInfo) {
      return backend().computeStorageBuilder().index(index(testInfo));
    }

    @Override public void clear() throws IOException {
      storage.clear();
    }
  }

  @Nested
  class ITStrictTraceIdFalse extends zipkin2.storage.ITStrictTraceIdFalse<ElasticsearchStorage> {
    @Override protected StorageComponent.Builder newStorageBuilder(TestInfo testInfo) {
      return backend().computeStorageBuilder().index(index(testInfo));
    }

    @Override public void clear() throws IOException {
      storage.clear();
    }
  }

  @Nested
  class ITDependencies extends zipkin2.storage.ITDependencies<ElasticsearchStorage> {
    @Override protected StorageComponent.Builder newStorageBuilder(TestInfo testInfo) {
      return backend().computeStorageBuilder().index(index(testInfo));
    }

    @Override protected void processDependencies(List<Span> spans) {
      aggregateDependencies(storage, spans);
    }

    @Override public void clear() throws IOException {
      storage.clear();
    }
  }

  @Nested
  class ITDependenciesHeavy extends zipkin2.storage.ITDependenciesHeavy<ElasticsearchStorage> {
    @Override protected StorageComponent.Builder newStorageBuilder(TestInfo testInfo) {
      return backend().computeStorageBuilder().index(index(testInfo));
    }

    @Override protected void processDependencies(List<Span> spans) {
      aggregateDependencies(storage, spans);
    }

    @Override public void clear() throws IOException {
      storage.clear();
    }
  }

  /**
   * The current implementation does not include dependency aggregation. It includes retrieval of
   * pre-aggregated links, usually made via zipkin-dependencies
   */
  static void aggregateDependencies(ElasticsearchStorage storage, List<Span> spans) {
    aggregateLinks(spans).forEach(
      (midnight, links) -> InternalForTests.writeDependencyLinks(
        storage, links, midnight));
  }

  @Test void testUsageOfDeprecatedFeatures() {
    WebClient webClient = WebClient.builder(backend().baseUrl()).factory(ClientFactory.builder()
      .useHttp2Preface(false).build()).build();
    final AggregatedHttpResponse response =
      webClient.execute(HttpRequest.of(RequestHeaders.of(HttpMethod.GET,
        "/_migration/deprecations"))).aggregate().join();
    if (!response.contentAscii().isEmpty()) {
      LOGGER.warn("The ElasticSearch instance used during IT's is using deprecated features or "
        + "configuration. This is likely nothing to be really worried about (for example 'xpack.monitoring.enabled' "
        + "setting), but nevertheless it should be looked at to see if our docker image used during "
        + "integration tests needs updating for the next version of ElasticSearch. "
        + "See https://www.elastic.co/guide/en/elasticsearch/reference/current/migration-api-deprecation.html"
        + "for more information. This is the deprecation warning we received:\n\n"
        + response.contentAscii());
    }
  }
}
