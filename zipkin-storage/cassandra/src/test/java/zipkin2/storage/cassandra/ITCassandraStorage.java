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

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.RegisterExtension;
import zipkin2.Span;
import zipkin2.storage.StorageComponent.Builder;

import static java.util.Arrays.asList;
import static zipkin2.storage.cassandra.InternalForTests.writeDependencyLinks;
import static zipkin2.storage.cassandra.Schema.TABLE_AUTOCOMPLETE_TAGS;
import static zipkin2.storage.cassandra.Schema.TABLE_SERVICE_REMOTE_SERVICES;
import static zipkin2.storage.cassandra.Schema.TABLE_SERVICE_SPANS;
import static zipkin2.storage.cassandra.Schema.TABLE_TRACE_BY_SERVICE_REMOTE_SERVICE;
import static zipkin2.storage.cassandra.Schema.TABLE_TRACE_BY_SERVICE_SPAN;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ITCassandraStorage {
  static final List<String> SEARCH_TABLES = asList(
    TABLE_AUTOCOMPLETE_TAGS,
    TABLE_SERVICE_REMOTE_SERVICES,
    TABLE_SERVICE_SPANS,
    TABLE_TRACE_BY_SERVICE_REMOTE_SERVICE,
    TABLE_TRACE_BY_SERVICE_SPAN
  );

  @RegisterExtension CassandraStorageExtension backend = new CassandraStorageExtension(
    "openzipkin/zipkin-cassandra:2.21.7");

  @Nested
  class ITTraces extends zipkin2.storage.ITTraces<CassandraStorage> {
    @Override protected Builder newStorageBuilder(TestInfo testInfo) {
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
    @Override protected Builder newStorageBuilder(TestInfo testInfo) {
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
  class ITSearchEnabledFalse extends zipkin2.storage.ITSearchEnabledFalse<CassandraStorage> {
    @Override protected Builder newStorageBuilder(TestInfo testInfo) {
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
    CassandraStorage strictTraceId;

    @Override protected Builder newStorageBuilder(TestInfo testInfo) {
      return backend.newStorageBuilder();
    }

    @BeforeEach void initializeStorageBeforeSwitch() {
      strictTraceId = CassandraStorageExtension.newStorageBuilder(storage.contactPoints)
        .keyspace(storage.keyspace)
        .build();
    }

    @AfterEach void closeStorageBeforeSwitch() {
      if (strictTraceId != null) {
        strictTraceId.close();
        strictTraceId = null;
      }
    }

    /** Ensures we can still lookup fully 128-bit traces when strict trace ID id disabled */
    @Test public void getTraces_128BitTraceId(TestInfo testInfo) throws Exception {
      getTraces_128BitTraceId(accept128BitTrace(strictTraceId, testInfo), testInfo);
    }

    /** Ensures data written before strict trace ID was enabled can be read */
    @Test
    public void getTrace_retrievesBy128BitTraceId_afterSwitch(TestInfo testInfo) throws Exception {
      List<Span> trace = accept128BitTrace(strictTraceId, testInfo);

      assertGetTraceReturns(trace.get(0).traceId(), trace);
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
    @Override protected Builder newStorageBuilder(TestInfo testInfo) {
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
    @Override protected Builder newStorageBuilder(TestInfo testInfo) {
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
    @Override protected Builder newStorageBuilder(TestInfo testInfo) {
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
  class ITSpanConsumer extends zipkin2.storage.cassandra.ITSpanConsumer {
    @Override protected Builder newStorageBuilder(TestInfo testInfo) {
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
