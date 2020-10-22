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
import com.datastax.oss.driver.api.core.cql.AsyncResultSet;
import com.datastax.oss.driver.api.core.cql.PreparedStatement;
import com.google.auto.value.AutoValue;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import zipkin2.Call;
import zipkin2.storage.cassandra.CassandraSpanStore.TimestampRange;
import zipkin2.storage.cassandra.internal.call.AccumulateTraceIdTsUuid;
import zipkin2.storage.cassandra.internal.call.AggregateIntoMap;
import zipkin2.storage.cassandra.internal.call.ResultSetFutureCall;

import static zipkin2.storage.cassandra.Schema.TABLE_TRACE_BY_SERVICE_REMOTE_SERVICE;

final class SelectTraceIdsFromServiceRemoteService extends ResultSetFutureCall<AsyncResultSet> {
  @AutoValue abstract static class Input {
    abstract String service();

    abstract String remote_service();

    abstract int bucket();

    abstract UUID start_ts();

    abstract UUID end_ts();

    abstract int limit_();

    Input withService(String service) {
      return new AutoValue_SelectTraceIdsFromServiceRemoteService_Input(
        service,
        remote_service(),
        bucket(),
        start_ts(),
        end_ts(),
        limit_());
    }
  }

  static final class Factory {
    final CqlSession session;
    final PreparedStatement preparedStatement;

    Factory(CqlSession session) {
      this.session = session;
      this.preparedStatement = session.prepare("SELECT trace_id,ts"
        + " FROM " + TABLE_TRACE_BY_SERVICE_REMOTE_SERVICE
        + " WHERE service=? AND remote_service=?"
        + " AND bucket=?"
        + " AND ts>=?"
        + " AND ts<=?"
        + " LIMIT ?");
    }

    Input newInput(
      String serviceName,
      String remoteServiceName,
      int bucket,
      TimestampRange timestampRange,
      int limit) {
      return new AutoValue_SelectTraceIdsFromServiceRemoteService_Input(
        serviceName,
        remoteServiceName,
        bucket,
        timestampRange.startUUID,
        timestampRange.endUUID,
        limit);
    }

    Call<Map<String, Long>> newCall(List<Input> inputs) {
      if (inputs.isEmpty()) return Call.create(Collections.emptyMap());
      if (inputs.size() == 1) return newCall(inputs.get(0));

      List<Call<Map<String, Long>>> bucketedTraceIdCalls = new ArrayList<>();
      for (SelectTraceIdsFromServiceRemoteService.Input input : inputs) {
        bucketedTraceIdCalls.add(newCall(input));
      }
      return new AggregateIntoMap<>(bucketedTraceIdCalls);
    }

    Call<Map<String, Long>> newCall(Input input) {
      return new SelectTraceIdsFromServiceRemoteService(this, preparedStatement, input)
        .flatMap(AccumulateTraceIdTsUuid.get());
    }

    /** Applies all deferred service names to all input templates */
    FlatMapper<List<String>, Map<String, Long>> newFlatMapper(List<Input> inputTemplates) {
      return new FlatMapServicesToInputs(inputTemplates);
    }

    class FlatMapServicesToInputs implements FlatMapper<List<String>, Map<String, Long>> {
      final List<Input> inputTemplates;

      FlatMapServicesToInputs(List<Input> inputTemplates) {
        this.inputTemplates = inputTemplates;
      }

      @Override public Call<Map<String, Long>> map(List<String> serviceNames) {
        List<Call<Map<String, Long>>> bucketedTraceIdCalls = new ArrayList<>();

        for (String service : serviceNames) { // fan out every input for each service name
          List<Input> scopedInputs = new ArrayList<>();
          for (Input input : inputTemplates) {
            scopedInputs.add(input.withService(service));
          }
          bucketedTraceIdCalls.add(newCall(scopedInputs));
        }

        if (bucketedTraceIdCalls.isEmpty()) return Call.create(Collections.emptyMap());
        if (bucketedTraceIdCalls.size() == 1) return bucketedTraceIdCalls.get(0);
        return new AggregateIntoMap<>(bucketedTraceIdCalls);
      }

      @Override public String toString() {
        List<String> inputs = new ArrayList<>();
        for (Input input : inputTemplates) {
          inputs.add(input.toString().replace("Input", "SelectTraceIdsFromServiceRemoteService"));
        }
        return "FlatMapServicesToInputs{" + inputs + "}";
      }
    }
  }

  final Factory factory;
  final PreparedStatement preparedStatement;
  final Input input;

  SelectTraceIdsFromServiceRemoteService(Factory factory, PreparedStatement preparedStatement,
    Input input) {
    this.factory = factory;
    this.preparedStatement = preparedStatement;
    this.input = input;
  }

  @Override protected CompletionStage<AsyncResultSet> newCompletionStage() {
    return factory.session.executeAsync(preparedStatement.boundStatementBuilder()
      .setString(0, input.service())
      .setString(1, input.remote_service())
      .setInt(2, input.bucket())
      .setUuid(3, input.start_ts())
      .setUuid(4, input.end_ts())
      .setInt(5, input.limit_())
      .setPageSize(input.limit_()).build());
  }

  @Override public AsyncResultSet map(AsyncResultSet input) {
    return input;
  }

  @Override public String toString() {
    return input.toString().replace("Input", "SelectTraceIdsFromServiceRemoteService");
  }

  @Override public SelectTraceIdsFromServiceRemoteService clone() {
    return new SelectTraceIdsFromServiceRemoteService(factory, preparedStatement, input);
  }
}
