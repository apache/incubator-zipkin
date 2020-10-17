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

import com.datastax.driver.core.ProtocolVersion;
import java.util.Collections;
import org.junit.Test;
import org.mockito.Mockito;
import zipkin2.Call;
import zipkin2.Endpoint;
import zipkin2.Span;
import zipkin2.internal.AggregateCall;
import zipkin2.storage.cassandra.internal.call.ResultSetFutureCall;

import static java.util.Arrays.asList;
import static java.util.Collections.singletonList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static zipkin2.TestObjects.BACKEND;
import static zipkin2.TestObjects.FRONTEND;
import static zipkin2.TestObjects.TODAY;

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

  @Test public void doesntIndexWhenOnlyIncludesTimestamp() {
    Span span = Span.newBuilder().traceId("a").id("1").timestamp(TODAY * 1000L).build();

    assertThat(consumer.accept(singletonList(span)))
      .isInstanceOf(ResultSetFutureCall.class);
  }

  @Test public void doesntIndexWhenMissingLocalServiceName() {
    Span span = spanWithoutAnnotationsOrTags.toBuilder().localEndpoint(null).build();

    assertThat(consumer.accept(singletonList(span)))
      .isInstanceOf(ResultSetFutureCall.class);
  }

  @Test public void indexesLocalServiceNameAndSpanName() {
    Span span = spanWithoutAnnotationsOrTags;

    AggregateCall<?, Void> call = (AggregateCall<?, Void>) consumer.accept(singletonList(span));
    assertThat(call.delegate())
      .filteredOn(c -> c instanceof IndexTraceId)
      .extracting("input.partitionKey")
      .containsExactly("frontend", "frontend.get");
  }

  @Test public void searchDisabled_doesntIndex() {
    consumer = spanConsumer(CassandraStorage.newBuilder().searchEnabled(false));

    Span span = spanWithoutAnnotationsOrTags.toBuilder()
      .addAnnotation(TODAY * 1000L, "annotation")
      .putTag("foo", "bar")
      .duration(10000L)
      .build();

    assertThat(consumer.accept(singletonList(span)))
      .isInstanceOf(InsertTrace.class);
  }

  @Test public void doesntIndexWhenMissingTimestamp() {
    Span span = spanWithoutAnnotationsOrTags.toBuilder().timestamp(null).build();

    assertThat(consumer.accept(singletonList(span)))
      .isInstanceOf(InsertTrace.class);
  }

  @Test public void indexKeysBasedOnLocalServiceNotRemote() {
    Span span = spanWithoutAnnotationsOrTags.toBuilder().remoteEndpoint(BACKEND).build();

    AggregateCall<?, Void> call = (AggregateCall<?, Void>) consumer.accept(singletonList(span));
    assertThat(call.delegate())
      .filteredOn(c -> c instanceof IndexTraceId)
      .extracting("input.partitionKey")
      .containsExactly("frontend", "frontend.backend", "frontend.get");
  }

  @Test public void indexesServiceNameWhenNoSpanName() {
    Span span = spanWithoutAnnotationsOrTags.toBuilder().name(null).build();

    AggregateCall<?, Void> call = (AggregateCall<?, Void>) consumer.accept(singletonList(span));
    assertThat(call.delegate())
      .filteredOn(c -> c instanceof IndexTraceId)
      .extracting("input.partitionKey")
      .containsExactly(FRONTEND.serviceName());
  }

  /**
   * Most partition keys will not clash, as they are delimited differently. For example, spans index
   * partition keys are delimited with dots, and annotations with colons.
   *
   * <p>This tests an edge case, where a delimiter exists in a service name.
   */
  @Test public void treatsIndexesSeparately() {
    Span span1 = Span.newBuilder()
      .traceId("1")
      .id("2")
      .name("foo")
      .timestamp(TODAY * 1000L)
      .localEndpoint(Endpoint.newBuilder().serviceName("app").build())
      .remoteEndpoint(Endpoint.newBuilder().serviceName("foo").build())
      .build();

    Span span2 = Span.newBuilder()
      .traceId("1")
      .id("3")
      .timestamp(TODAY * 1000L)
      .localEndpoint(Endpoint.newBuilder().serviceName("app.foo").build())
      .build();

    AggregateCall<?, Void> call = (AggregateCall<?, Void>) consumer.accept(asList(span1, span2));
    assertThat(call.delegate())
      .filteredOn(c -> c instanceof IndexTraceId)
      .extracting("factory.indexerFactory.table", "input.partitionKey")
      .containsExactly(
        tuple(Tables.SERVICE_NAME_INDEX, "app"),
        tuple(Tables.SERVICE_NAME_INDEX, "app.foo"),
        tuple(Tables.SERVICE_REMOTE_SERVICE_NAME_INDEX, "app.foo"),
        tuple(Tables.SERVICE_SPAN_NAME_INDEX, "app.foo")
      );

    // intentionally redundantly accept span2 which double-checks deduplication of index calls
    assertThat(consumer.accept(singletonList(span2)))
      .isInstanceOf(InsertTrace.class);
  }

  static CassandraSpanConsumer spanConsumer(CassandraStorage.Builder builder) {
    CassandraStorage storage =
      spy(builder.sessionFactory(mock(SessionFactory.class, Mockito.RETURNS_MOCKS)).build());
    doReturn(new Schema.Metadata(ProtocolVersion.V4, "", true, true, true))
      .when(storage).metadata();
    return new CassandraSpanConsumer(storage);
  }
}
