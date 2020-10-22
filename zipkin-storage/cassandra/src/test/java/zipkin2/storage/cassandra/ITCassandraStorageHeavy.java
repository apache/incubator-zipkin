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

import com.datastax.oss.driver.api.core.CqlSession;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.RegisterExtension;
import zipkin2.Span;
import zipkin2.storage.QueryRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static zipkin2.TestObjects.DAY;
import static zipkin2.TestObjects.FRONTEND;
import static zipkin2.TestObjects.appendSuffix;
import static zipkin2.TestObjects.newTrace;
import static zipkin2.storage.ITDependencies.aggregateLinks;
import static zipkin2.storage.cassandra.InternalForTests.writeDependencyLinks;

/**
 * Large amounts of writes can make other tests flake. This can happen for reasons such as
 * overloading the test Cassandra container or knock-on effects of tombstones left from {@link
 * CassandraStorageExtension#clear(CassandraStorage)}.
 *
 * <p>Tests here share a different Cassandra container and each method runs in an isolated
 * keyspace. As schema installation takes ~10s, hesitate adding too many tests here.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ITCassandraStorageHeavy {

  @RegisterExtension CassandraStorageExtension backend =
    new CassandraStorageExtension("openzipkin/zipkin-cassandra:2.21.7");

  @Nested
  class ITSpanStoreHeavy extends zipkin2.storage.ITSpanStoreHeavy<CassandraStorage> {
    @Override protected CassandraStorage.Builder newStorageBuilder(TestInfo testInfo) {
      return backend.newStorageBuilder().keyspace(InternalForTests.keyspace(testInfo));
    }

    @Override protected void blockWhileInFlight() {
      CassandraStorageExtension.blockWhileInFlight(storage);
    }

    @Override public void clear() {
      // Intentionally don't clean up as each method runs in an isolated keyspace. This prevents
      // adding more load to the shared Cassandra instance used for all tests.
    }

    @Test void overFetchesToCompensateForDuplicateIndexData(TestInfo testInfo) throws Exception {
      String testSuffix = testSuffix(testInfo);
      int traceCount = 2000;

      List<Span> spans = new ArrayList<>();
      for (int i = 0; i < traceCount; i++) {
        final long delta = i * 1000; // all timestamps happen a millisecond later
        for (Span s : newTrace(testSuffix)) {
          Span.Builder builder = s.toBuilder()
            .timestamp(s.timestampAsLong() + delta)
            .clearAnnotations();
          s.annotations().forEach(a -> builder.addAnnotation(a.timestamp() + delta, a.value()));
          spans.add(builder.build());
        }
      }

      accept(spans.toArray(new Span[0]));

      // Index ends up containing more rows than services * trace count, and cannot be de-duped
      // in a server-side query.
      int localServiceCount = storage.serviceAndSpanNames().getServiceNames().execute().size();
      assertThat(storage
        .session()
        .execute("SELECT COUNT(*) from trace_by_service_span")
        .one()
        .getLong(0))
        .isGreaterThan(traceCount * localServiceCount);

      // Implementation over-fetches on the index to allow the user to receive unsurprising results.
      QueryRequest request = requestBuilder()
        // Ensure we use serviceName so that trace_by_service_span is used
        .serviceName(appendSuffix(FRONTEND.serviceName(), testSuffix))
        .lookback(DAY).limit(traceCount).build();
      assertThat(store().getTraces(request).execute())
        .hasSize(traceCount);
    }
  }

  @Nested
  class ITDependenciesHeavy extends zipkin2.storage.ITDependenciesHeavy<CassandraStorage> {
    @Override protected CassandraStorage.Builder newStorageBuilder(TestInfo testInfo) {
      return backend.newStorageBuilder().keyspace(InternalForTests.keyspace(testInfo));
    }

    @Override protected void blockWhileInFlight() {
      CassandraStorageExtension.blockWhileInFlight(storage);
    }

    @Override public void clear() {
      // Intentionally don't clean up as each method runs in an isolated keyspace. This prevents
      // adding more load to the shared Cassandra instance used for all tests.
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
  class ITEnsureSchema extends zipkin2.storage.cassandra.ITEnsureSchema {
    @Override protected CassandraStorage.Builder newStorageBuilder(TestInfo testInfo) {
      return backend.newStorageBuilder().keyspace(InternalForTests.keyspace(testInfo));
    }

    @Override CqlSession session() {
      return backend.globalSession;
    }

    @Override protected void blockWhileInFlight() {
      CassandraStorageExtension.blockWhileInFlight(storage);
    }

    @Override public void clear() {
      // Intentionally don't clean up as each method runs in an isolated keyspace. This prevents
      // adding more load to the shared Cassandra instance used for all tests.
    }
  }
}
