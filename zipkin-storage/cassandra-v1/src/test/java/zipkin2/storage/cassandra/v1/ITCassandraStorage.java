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
package zipkin2.storage.cassandra.v1;

import java.util.List;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.RegisterExtension;
import zipkin2.Span;
import zipkin2.storage.StorageComponent;

import static java.util.Arrays.asList;
import static zipkin2.storage.cassandra.v1.InternalForTests.writeDependencyLinks;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ITCassandraStorage {
  static final List<String> SEARCH_TABLES = asList(
    Tables.AUTOCOMPLETE_TAGS,
    Tables.REMOTE_SERVICE_NAMES,
    Tables.SPAN_NAMES,
    Tables.SERVICE_NAMES,
    Tables.ANNOTATIONS_INDEX,
    Tables.SERVICE_REMOTE_SERVICE_NAME_INDEX,
    Tables.SERVICE_SPAN_NAME_INDEX,
    Tables.SERVICE_SPAN_NAME_INDEX
  );

  @RegisterExtension CassandraStorageExtension backend =
    new CassandraStorageExtension("openzipkin/zipkin-cassandra:2.21.7");

  @Nested
  class ITTraces extends zipkin2.storage.ITTraces<CassandraStorage> {
    @Override protected StorageComponent.Builder newStorageBuilder(TestInfo testInfo) {
      return backend.newStorageBuilder();
    }

    @Override @Test @Disabled("No consumer-side span deduplication")
    public void getTrace_deduplicates(TestInfo testInfo) {
    }

    @Override protected void blockWhileInFlight() {
      CassandraStorageExtension.blockWhileInFlight(storage);
    }

    @Override public void clear() {
      backend.clear(storage);
    }
  }

  @Nested
  class ITSpanStore extends zipkin2.storage.ITSpanStore<CassandraStorage> {
    @Override protected StorageComponent.Builder newStorageBuilder(TestInfo testInfo) {
      return backend.newStorageBuilder();
    }

    @Override @Test @Disabled("All services query unsupported when combined with other qualifiers")
    public void getTraces_tags(TestInfo testInfo) {
    }

    @Override @Test @Disabled("All services query unsupported when combined with other qualifiers")
    public void getTraces_serviceNames(TestInfo testInfo) {
    }

    @Override @Test @Disabled("All services query unsupported when combined with other qualifiers")
    public void getTraces_spanName(TestInfo testInfo) {
    }

    @Override @Test @Disabled("All services query unsupported when combined with other qualifiers")
    public void getTraces_spanName_mixedTraceIdLength(TestInfo testInfo) {
    }

    @Override @Test @Disabled("Duration unsupported")
    public void getTraces_duration(TestInfo testInfo) {
    }

    @Override @Test @Disabled("Duration unsupported")
    public void getTraces_minDuration(TestInfo testInfo) {
    }

    @Override @Test @Disabled("Duration unsupported")
    public void getTraces_maxDuration(TestInfo testInfo) {
    }

    @Override @Test @Disabled("Duration unsupported")
    public void getTraces_lateDuration(TestInfo testInfo) {
    }

    @Override protected void blockWhileInFlight() {
      CassandraStorageExtension.blockWhileInFlight(storage);
    }

    @Override public void clear() {
      backend.clear(storage);
    }
  }

  @Nested
  class ITSearchEnabledFalse extends zipkin2.storage.ITSearchEnabledFalse<CassandraStorage> {
    @Override protected StorageComponent.Builder newStorageBuilder(TestInfo testInfo) {
      return backend.newStorageBuilder();
    }

    @Override protected void blockWhileInFlight() {
      CassandraStorageExtension.blockWhileInFlight(storage);
    }

    @Override public void clear() {
      backend.clear(storage);
    }
  }

  @Nested
  class ITStrictTraceIdFalse extends zipkin2.storage.ITStrictTraceIdFalse<CassandraStorage> {
    @Override protected StorageComponent.Builder newStorageBuilder(TestInfo testInfo) {
      return backend.newStorageBuilder();
    }

    @Override protected void blockWhileInFlight() {
      CassandraStorageExtension.blockWhileInFlight(storage);
    }

    @Override public void clear() {
      backend.clear(storage);
    }
  }

  @Nested
  class ITServiceAndSpanNames extends zipkin2.storage.ITServiceAndSpanNames<CassandraStorage> {
    @Override protected StorageComponent.Builder newStorageBuilder(TestInfo testInfo) {
      return backend.newStorageBuilder();
    }

    @Override protected void blockWhileInFlight() {
      CassandraStorageExtension.blockWhileInFlight(storage);
    }

    @Override public void clear() {
      backend.clear(storage);
    }
  }

  @Nested
  class ITAutocompleteTags extends zipkin2.storage.ITAutocompleteTags<CassandraStorage> {
    @Override protected StorageComponent.Builder newStorageBuilder(TestInfo testInfo) {
      return backend.newStorageBuilder();
    }

    @Override protected void blockWhileInFlight() {
      CassandraStorageExtension.blockWhileInFlight(storage);
    }

    @Override public void clear() {
      backend.clear(storage);
    }
  }

  @Nested
  class ITDependencies extends zipkin2.storage.ITDependencies<CassandraStorage> {
    @Override protected StorageComponent.Builder newStorageBuilder(TestInfo testInfo) {
      return backend.newStorageBuilder();
    }

    @Override protected void blockWhileInFlight() {
      CassandraStorageExtension.blockWhileInFlight(storage);
    }

    @Override public void clear() {
      backend.clear(storage);
    }

    /**
     * The current implementation does not include dependency aggregation. It includes retrieval of
     * pre-aggregated links, usually made via zipkin-dependencies
     */
    @Override protected void processDependencies(List<Span> spans) {
      aggregateLinks(spans).forEach(
        (midnight, links) -> writeDependencyLinks(storage, links, midnight));
      blockWhileInFlight();
    }
  }

  @Nested
  class ITSpanConsumer extends zipkin2.storage.cassandra.v1.ITSpanConsumer {
    @Override protected StorageComponent.Builder newStorageBuilder(TestInfo testInfo) {
      return backend.newStorageBuilder();
    }

    @Override protected void blockWhileInFlight() {
      CassandraStorageExtension.blockWhileInFlight(storage);
    }

    @Override public void clear() {
      backend.clear(storage);
    }
  }
}
