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
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import zipkin2.Call;
import zipkin2.Span;
import zipkin2.internal.AggregateCall;
import zipkin2.storage.ITStorage;
import zipkin2.storage.StorageComponent;
import zipkin2.storage.cassandra.internal.call.InsertEntry;

import static java.util.Arrays.asList;
import static org.assertj.core.api.Assertions.assertThat;
import static zipkin2.Span.Kind.SERVER;
import static zipkin2.TestObjects.CLIENT_SPAN;
import static zipkin2.TestObjects.newClientSpan;
import static zipkin2.TestObjects.spanBuilder;

abstract class ITSpanConsumer extends ITStorage<CassandraStorage> {
  @Override protected void configureStorageForTest(StorageComponent.Builder storage) {
    storage.autocompleteKeys(asList("environment"));
  }

  /**
   * {@link Span#timestamp()} == 0 is likely to be a mistake, and coerces to null. It is not helpful
   * to index rows who have no timestamp.
   */
  @Test public void doesntIndexSpansMissingTimestamp(TestInfo testInfo) throws Exception {
    String testSuffix = testSuffix(testInfo);
    accept(spanBuilder(testSuffix).timestamp(0L).duration(0L).build());

    assertThat(rowCountForTraceByServiceSpan(storage)).isZero();
  }

  /**
   * Simulates a trace with a step pattern, where each span starts a millisecond after the prior
   * one. The consumer code optimizes index inserts to only represent the interval represented by
   * the trace as opposed to each individual timestamp.
   */
  @Test public void skipsRedundantIndexingInATrace(TestInfo testInfo) throws Exception {
    String testSuffix = testSuffix(testInfo);
    Span[] trace = new Span[101];
    trace[0] = newClientSpan(testSuffix).toBuilder().kind(SERVER).build();

    IntStream.range(0, 100).forEach(i -> trace[i + 1] = Span.newBuilder()
      .traceId(trace[0].traceId())
      .parentId(trace[0].id())
      .id(i + 1)
      .name("get")
      .kind(Span.Kind.CLIENT)
      .localEndpoint(trace[0].localEndpoint())
      .timestamp(trace[0].timestampAsLong() + i * 1000) // all peer span timestamps happen 1ms later
      .duration(10L)
      .build());

    accept(trace);
    assertThat(rowCountForTraceByServiceSpan(storage))
      .isGreaterThanOrEqualTo(4L);
    assertThat(rowCountForTraceByServiceSpan(storage))
      .isGreaterThanOrEqualTo(4L);

    CassandraSpanConsumer withoutStrictTraceId = new CassandraSpanConsumer(
      storage.session(), storage.metadata(),
      false /* strictTraceId */, storage.searchEnabled,
      storage.autocompleteKeys, storage.autocompleteTtl, storage.autocompleteCardinality
    );

    // sanity check base case
    withoutStrictTraceId.accept(asList(trace)).execute();
    blockWhileInFlight();

    assertThat(rowCountForTraceByServiceSpan(storage))
      .isGreaterThanOrEqualTo(120L); // TODO: magic number
    assertThat(rowCountForTraceByServiceSpan(storage))
      .isGreaterThanOrEqualTo(120L);
  }

  @Test public void insertTags_SelectTags_CalculateCount(TestInfo testInfo) throws Exception {
    String testSuffix = testSuffix(testInfo);
    Span[] trace = new Span[101];
    trace[0] = newClientSpan(testSuffix).toBuilder().kind(SERVER).build();

    IntStream.range(0, 100).forEach(i -> trace[i + 1] = Span.newBuilder()
      .traceId(trace[0].traceId())
      .parentId(trace[0].id())
      .id(i + 1)
      .name("get")
      .kind(Span.Kind.CLIENT)
      .localEndpoint(trace[0].localEndpoint())
      .putTag("environment", "dev")
      .putTag("a", "b")
      .timestamp(trace[0].timestampAsLong() + i * 1000) // all peer span timestamps happen 1ms later
      .duration(10L)
      .build());

    accept(trace);

    assertThat(rowCountForTags(storage))
      .isEqualTo(1L); // Since tag {a,b} are not in the whitelist

    assertThat(getTagValue(storage, "environment")).isEqualTo("dev");
  }

  /** It is easier to use a real Cassandra connection than mock a prepared statement. */
  @Test public void insertEntry_niceToString() {
    // This test can use fake data as it is never written to cassandra
    Span clientSpan = CLIENT_SPAN;

    AggregateCall<?, ?> acceptCall =
      (AggregateCall<?, ?>) storage.spanConsumer().accept(asList(clientSpan));

    List<Call<?>> insertEntryCalls = acceptCall.delegate().stream()
      .filter(c -> c instanceof InsertEntry)
      .collect(Collectors.toList());

    assertThat(insertEntryCalls.get(0))
      .hasToString("INSERT INTO span_by_service (service, span) VALUES (frontend,get)");
    assertThat(insertEntryCalls.get(1))
      .hasToString(
        "INSERT INTO remote_service_by_service (service, remote_service) VALUES (frontend,backend)");
  }

  static long rowCountForTraceByServiceSpan(CassandraStorage storage) {
    return storage
      .session()
      .execute("SELECT COUNT(*) from " + Schema.TABLE_TRACE_BY_SERVICE_SPAN)
      .one()
      .getLong(0);
  }

  static long rowCountForTags(CassandraStorage storage) {
    return storage
      .session()
      .execute("SELECT COUNT(*) from " + Schema.TABLE_AUTOCOMPLETE_TAGS)
      .one()
      .getLong(0);
  }

  static String getTagValue(CassandraStorage storage, String key) {
    return storage
      .session()
      .execute("SELECT value from " + Schema.TABLE_AUTOCOMPLETE_TAGS + " WHERE key='environment'")
      .one()
      .getString(0);
  }
}
