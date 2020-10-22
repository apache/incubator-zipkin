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
import java.util.List;
import org.junit.Test;
import zipkin2.Call;
import zipkin2.Span;
import zipkin2.storage.QueryRequest;
import zipkin2.storage.cassandra.SelectTraceIdsFromServiceSpan.Factory.FlatMapServicesToInputs;

import static org.assertj.core.api.Assertions.assertThat;
import static zipkin2.TestObjects.DAY;
import static zipkin2.TestObjects.TODAY;
import static zipkin2.storage.cassandra.InternalForTests.mockSession;

// TODO: tests use toString because the call composition chain is complex (includes flat mapping)
// This could be made a little less complex if we scrub out map=>map to a list of transformations,
// or possibly special-casing common transformations.
public class CassandraSpanStoreTest {
  CassandraSpanStore spanStore = spanStore(CassandraStorage.newBuilder());
  QueryRequest.Builder queryBuilder = QueryRequest.newBuilder().endTs(TODAY).lookback(DAY).limit(5);

  @Test public void getTraces_fansOutAgainstServices() {
    Call<List<List<Span>>> call = spanStore.getTraces(queryBuilder.build());

    assertThat(call.toString()).contains(FlatMapServicesToInputs.class.getSimpleName());
  }

  @Test public void getTraces_withSpanNameButNoServiceName() {
    Call<List<List<Span>>> call = spanStore.getTraces(queryBuilder.spanName("get").build());

    assertThat(call.toString())
      .contains(FlatMapServicesToInputs.class.getSimpleName())
      .contains("span=get"); // no need to look at two indexes
  }

  @Test public void getTraces_withTagButNoServiceName() {
    Call<List<List<Span>>> call = spanStore.getTraces(
      queryBuilder.annotationQuery(Collections.singletonMap("environment", "production")).build());

    assertThat(call.toString())
      .doesNotContain(FlatMapServicesToInputs.class.getSimpleName()) // works against the span table
      .contains("l_service=null, annotation_query=environment=production");
  }

  @Test public void getTraces_withDurationButNoServiceName() {
    Call<List<List<Span>>> call = spanStore.getTraces(queryBuilder.minDuration(1000L).build());

    assertThat(call.toString())
      .contains(FlatMapServicesToInputs.class.getSimpleName())
      .contains("start_duration=1,");
  }

  @Test public void getTraces_withRemoteServiceNameButNoServiceName() {
    Call<List<List<Span>>> call =
      spanStore.getTraces(queryBuilder.remoteServiceName("backend").build());

    assertThat(call.toString())
      .contains(FlatMapServicesToInputs.class.getSimpleName())
      .contains("remote_service=backend,")
      .doesNotContain("span="); // no need to look at two indexes
  }

  @Test public void getTraces() {
    Call<List<List<Span>>> call = spanStore.getTraces(queryBuilder.serviceName("frontend").build());

    assertThat(call.toString()).contains("service=frontend, span=,");
  }

  @Test public void getTraces_withSpanName() {
    Call<List<List<Span>>> call = spanStore.getTraces(
      queryBuilder.serviceName("frontend").spanName("get").build());

    assertThat(call.toString())
      .contains("service=frontend, span=get,");
  }

  @Test public void getTraces_withRemoteServiceName() {
    Call<List<List<Span>>> call = spanStore.getTraces(
      queryBuilder.serviceName("frontend").remoteServiceName("backend").build());

    assertThat(call.toString())
      .contains("service=frontend, remote_service=backend,")
      .doesNotContain("service=frontend, span="); // no need to look at two indexes
  }

  @Test public void getTraces_withSpanNameAndRemoteServiceName() {
    Call<List<List<Span>>> call = spanStore.getTraces(
      queryBuilder.serviceName("frontend").remoteServiceName("backend").spanName("get").build());

    assertThat(call.toString()) // needs to look at two indexes
      .contains("service=frontend, remote_service=backend,")
      .contains("service=frontend, span=get,");
  }

  @Test public void searchDisabled_doesntMakeRemoteQueryRequests() {
    CassandraSpanStore spanStore = spanStore(CassandraStorage.newBuilder().searchEnabled(false));

    assertThat(spanStore.getTraces(queryBuilder.build())).hasToString("ConstantCall{value=[]}");
    assertThat(spanStore.getServiceNames()).hasToString("ConstantCall{value=[]}");
    assertThat(spanStore.getRemoteServiceNames("icecream")).hasToString("ConstantCall{value=[]}");
    assertThat(spanStore.getSpanNames("icecream")).hasToString("ConstantCall{value=[]}");
  }

  static CassandraSpanStore spanStore(CassandraStorage.Builder builder) {
    return new CassandraSpanStore(builder.sessionFactory(storage -> mockSession()).build());
  }
}
