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

import java.util.Collections;
import org.junit.Test;
import zipkin2.Call;
import zipkin2.Span;
import zipkin2.internal.AggregateCall;
import zipkin2.storage.cassandra.internal.call.InsertEntry;
import zipkin2.storage.cassandra.internal.call.ResultSetFutureCall;

import static java.util.Collections.singletonList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;
import static org.assertj.core.api.Assertions.tuple;
import static zipkin2.TestObjects.BACKEND;
import static zipkin2.TestObjects.FRONTEND;
import static zipkin2.TestObjects.TODAY;
import static zipkin2.storage.cassandra.InternalForTests.mockSession;

public class CassandraSpanConsumerTest {
  CassandraSpanConsumer consumer = spanConsumer(CassandraStorage.newBuilder());

  Span spanWithoutAnnotationsOrTags =
    Span.newBuilder()
      .traceId("a")
      .id("1")
      .name("get")
      .localEndpoint(FRONTEND)
      .timestamp(TODAY * 1000L)
      .duration(207000L)
      .build();

  @Test public void emptyInput_emptyCall() {
    Call<Void> call = consumer.accept(Collections.emptyList());
    assertThat(call).hasSameClassAs(Call.create(null));
  }

  @Test public void doesntSetTraceIdHigh_128() {
    Span span = spanWithoutAnnotationsOrTags.toBuilder()
      .traceId("77fcac3d4c5be8d2a037812820c65f28")
      .build();

    AggregateCall<?, Void> call = (AggregateCall<?, Void>) consumer.accept(singletonList(span));
    assertThat(call.delegate())
      .filteredOn(c -> c instanceof InsertSpan)
      .extracting("input.trace_id_high", "input.trace_id")
      .containsExactly(tuple(null, span.traceId()));
  }

  @Test public void doesntSetTraceIdHigh_64() {
    Span span = spanWithoutAnnotationsOrTags;

    AggregateCall<?, Void> call = (AggregateCall<?, Void>) consumer.accept(singletonList(span));
    assertThat(call.delegate())
      .filteredOn(c -> c instanceof InsertSpan)
      .extracting("input.trace_id_high", "input.trace_id")
      .containsExactly(tuple(null, span.traceId()));
  }

  @Test public void strictTraceIdFalse_setsTraceIdHigh() {
    consumer = spanConsumer(CassandraStorage.newBuilder().strictTraceId(false));

    Span span = spanWithoutAnnotationsOrTags.toBuilder()
      .traceId("77fcac3d4c5be8d2a037812820c65f28")
      .build();

    AggregateCall<?, Void> call = (AggregateCall<?, Void>) consumer.accept(singletonList(span));
    assertThat(call.delegate())
      .filteredOn(c -> c instanceof InsertSpan)
      .extracting("input.trace_id_high", "input.trace_id")
      .containsExactly(tuple("77fcac3d4c5be8d2", "a037812820c65f28"));
  }

  @Test public void serviceSpanKeys() {
    Span span = spanWithoutAnnotationsOrTags;

    AggregateCall<?, Void> call = (AggregateCall<?, Void>) consumer.accept(singletonList(span));
    assertThat(call.delegate())
      .filteredOn(c -> c instanceof InsertEntry)
      .extracting("input")
      .containsExactly(entry(FRONTEND.serviceName(), span.name()));
  }

  @Test public void serviceRemoteServiceKeys_addsRemoteServiceName() {
    Span span = spanWithoutAnnotationsOrTags.toBuilder().remoteEndpoint(BACKEND).build();

    AggregateCall<?, Void> call = (AggregateCall<?, Void>) consumer.accept(singletonList(span));
    assertThat(call.delegate())
      .filteredOn(c -> c instanceof InsertEntry)
      .extracting("input")
      .containsExactly(
        entry(FRONTEND.serviceName(), span.name()),
        entry(FRONTEND.serviceName(), BACKEND.serviceName())
      );
  }

  @Test public void serviceRemoteServiceKeys_skipsRemoteServiceNameWhenNoLocalService() {
    Span span = spanWithoutAnnotationsOrTags.toBuilder()
      .localEndpoint(null)
      .remoteEndpoint(BACKEND).build();

    Call<Void> call = consumer.accept(singletonList(span));

    assertThat(call).isInstanceOf(InsertSpan.class);
  }

  @Test public void serviceSpanKeys_emptyWhenNoEndpoints() {
    Span span = spanWithoutAnnotationsOrTags.toBuilder().localEndpoint(null).build();

    assertThat(consumer.accept(singletonList(span)))
      .isInstanceOf(ResultSetFutureCall.class);
  }

  /**
   * To allow lookups w/o a span name, we index "". "" is used instead of null to avoid creating
   * tombstones.
   */
  @Test public void traceByServiceSpan_indexesLocalServiceNameAndEmptySpanName() {
    Span span = spanWithoutAnnotationsOrTags;

    AggregateCall<?, Void> call = (AggregateCall<?, Void>) consumer.accept(singletonList(span));
    assertThat(call.delegate())
      .filteredOn(c -> c instanceof InsertTraceByServiceSpan)
      .extracting("input.service", "input.span")
      .containsExactly(
        tuple(FRONTEND.serviceName(), span.name()), tuple(FRONTEND.serviceName(), ""));
  }

  @Test public void traceByServiceSpan_indexesDurationInMillis() {
    Span span = spanWithoutAnnotationsOrTags;

    AggregateCall<?, Void> call = (AggregateCall<?, Void>) consumer.accept(singletonList(span));
    assertThat(call.delegate())
      .filteredOn(c -> c instanceof InsertTraceByServiceSpan)
      .extracting("input.duration")
      .containsOnly(span.durationAsLong() / 1000L);
  }

  @Test public void traceByServiceSpan_indexesDurationMinimumZero() {
    Span span = spanWithoutAnnotationsOrTags.toBuilder().duration(12L).build();

    AggregateCall<?, Void> call = (AggregateCall<?, Void>) consumer.accept(singletonList(span));
    assertThat(call.delegate())
      .filteredOn(c -> c instanceof InsertTraceByServiceSpan)
      .extracting("input.duration")
      .containsOnly(0L);
  }

  @Test public void traceByServiceSpan_skipsOnNoTimestamp() {
    Span span = spanWithoutAnnotationsOrTags.toBuilder().timestamp(null).build();

    AggregateCall<?, Void> call = (AggregateCall<?, Void>) consumer.accept(singletonList(span));
    assertThat(call.delegate())
      .filteredOn(c -> c instanceof InsertTraceByServiceSpan)
      .extracting("input.service", "input.span")
      .isEmpty();
  }

  @Test public void traceByServiceSpan_doesntIndexRemoteService() {
    Span span = spanWithoutAnnotationsOrTags.toBuilder().remoteEndpoint(BACKEND).build();

    AggregateCall<?, Void> call = (AggregateCall<?, Void>) consumer.accept(singletonList(span));
    assertThat(call.delegate())
      .filteredOn(c -> c instanceof InsertTraceByServiceSpan)
      .hasSize(2)
      .extracting("input.service")
      .doesNotContain(BACKEND.serviceName());
  }

  @Test public void traceByServiceSpan_appendsEmptyWhenNoName() {
    Span span = spanWithoutAnnotationsOrTags.toBuilder().name(null).build();

    AggregateCall<?, Void> call = (AggregateCall<?, Void>) consumer.accept(singletonList(span));
    assertThat(call.delegate())
      .filteredOn(c -> c instanceof InsertTraceByServiceSpan)
      .extracting("input.service", "input.span")
      .containsExactly(tuple(FRONTEND.serviceName(), ""));
  }

  @Test public void traceByServiceSpan_emptyWhenNoEndpoints() {
    Span span = spanWithoutAnnotationsOrTags.toBuilder().localEndpoint(null).build();

    assertThat(consumer.accept(singletonList(span)))
      .isInstanceOf(ResultSetFutureCall.class);
  }

  @Test public void searchDisabled_doesntIndex() {
    consumer = spanConsumer(CassandraStorage.newBuilder().searchEnabled(false));

    Span span = spanWithoutAnnotationsOrTags.toBuilder()
      .addAnnotation(TODAY * 1000L, "annotation")
      .putTag("foo", "bar")
      .duration(10000L)
      .build();

    assertThat(consumer.accept(singletonList(span)))
      .extracting("input.annotation_query")
      .satisfies(q -> assertThat(q).isNull());
  }

  @Test public void doesntIndexWhenOnlyIncludesTimestamp() {
    Span span = Span.newBuilder().traceId("a").id("1").timestamp(TODAY * 1000L).build();

    assertThat(consumer.accept(singletonList(span)))
      .isInstanceOf(ResultSetFutureCall.class);
  }

  static CassandraSpanConsumer spanConsumer(CassandraStorage.Builder builder) {
    return new CassandraSpanConsumer(builder.sessionFactory(storage -> mockSession()).build());
  }
}
