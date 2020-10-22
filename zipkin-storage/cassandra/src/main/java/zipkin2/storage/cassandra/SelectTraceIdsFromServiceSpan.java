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
import com.datastax.oss.driver.api.core.cql.BoundStatementBuilder;
import com.datastax.oss.driver.api.core.cql.PreparedStatement;
import com.google.auto.value.AutoValue;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import zipkin2.Call;
import zipkin2.internal.Nullable;
import zipkin2.storage.cassandra.CassandraSpanStore.TimestampRange;
import zipkin2.storage.cassandra.internal.call.AccumulateTraceIdTsUuid;
import zipkin2.storage.cassandra.internal.call.AggregateIntoMap;
import zipkin2.storage.cassandra.internal.call.ResultSetFutureCall;

import static zipkin2.storage.cassandra.Schema.TABLE_TRACE_BY_SERVICE_SPAN;

final class SelectTraceIdsFromServiceSpan extends ResultSetFutureCall<AsyncResultSet> {
  @AutoValue abstract static class Input {
    abstract String service();

    abstract String span();

    abstract int bucket();

    @Nullable abstract Long start_duration();

    @Nullable abstract Long end_duration();

    abstract UUID start_ts();

    abstract UUID end_ts();

    abstract int limit_();

    Input withService(String service) {
      return new AutoValue_SelectTraceIdsFromServiceSpan_Input(
        service,
        span(),
        bucket(),
        start_duration(),
        end_duration(),
        start_ts(),
        end_ts(),
        limit_());
    }
  }

  static final class Factory {
    final CqlSession session;
    final PreparedStatement selectTraceIdsByServiceSpanName;
    final PreparedStatement selectTraceIdsByServiceSpanNameAndDuration;

    Factory(CqlSession session) {
      this.session = session;
      String baseQuery = "SELECT trace_id,ts"
        + " FROM " + TABLE_TRACE_BY_SERVICE_SPAN
        + " WHERE service=?"
        + " AND span=?"
        + " AND bucket=?"
        + " AND ts>=?"
        + " AND ts<=?";
      this.selectTraceIdsByServiceSpanName = session.prepare(baseQuery
        + " LIMIT ?");
      this.selectTraceIdsByServiceSpanNameAndDuration = session.prepare(baseQuery
        + " AND duration>=?"
        + " AND duration<=?"
        + " LIMIT ?");
    }

    Input newInput(
      String serviceName,
      String spanName,
      int bucket,
      @Nullable Long minDurationMicros,
      @Nullable Long maxDurationMicros,
      TimestampRange timestampRange,
      int limit) {
      Long start_duration = null, end_duration = null;
      if (minDurationMicros != null) {
        start_duration = minDurationMicros / 1000L;
        end_duration = maxDurationMicros != null ? maxDurationMicros / 1000L : Long.MAX_VALUE;
      }
      return new AutoValue_SelectTraceIdsFromServiceSpan_Input(
        serviceName,
        spanName,
        bucket,
        start_duration,
        end_duration,
        timestampRange.startUUID,
        timestampRange.endUUID,
        limit);
    }

    Call<Map<String, Long>> newCall(List<Input> inputs) {
      if (inputs.isEmpty()) return Call.create(Collections.emptyMap());
      if (inputs.size() == 1) return newCall(inputs.get(0));

      List<Call<Map<String, Long>>> bucketedTraceIdCalls = new ArrayList<>();
      for (SelectTraceIdsFromServiceSpan.Input input : inputs) {
        bucketedTraceIdCalls.add(newCall(input));
      }
      return new AggregateIntoMap<>(bucketedTraceIdCalls);
    }

    Call<Map<String, Long>> newCall(Input input) {
      PreparedStatement preparedStatement = input.start_duration() != null
        ? selectTraceIdsByServiceSpanNameAndDuration
        : selectTraceIdsByServiceSpanName;
      return new SelectTraceIdsFromServiceSpan(this, preparedStatement, input)
        .flatMap(AccumulateTraceIdTsUuid.get());
    }

    /** Applies all deferred service names to all input templates */
    FlatMapper<List<String>, Map<String, Long>> newFlatMapper(List<Input> inputTemplates) {
      return new FlatMapServicesToInputs(inputTemplates);
    }

    class FlatMapServicesToInputs implements FlatMapper<List<String>, Map<String, Long>> {
      final List<SelectTraceIdsFromServiceSpan.Input> inputTemplates;

      FlatMapServicesToInputs(List<SelectTraceIdsFromServiceSpan.Input> inputTemplates) {
        this.inputTemplates = inputTemplates;
      }

      @Override public Call<Map<String, Long>> map(List<String> serviceNames) {
        List<Call<Map<String, Long>>> bucketedTraceIdCalls = new ArrayList<>();

        for (String service : serviceNames) { // fan out every input for each service name
          List<SelectTraceIdsFromServiceSpan.Input> scopedInputs = new ArrayList<>();
          for (SelectTraceIdsFromServiceSpan.Input input : inputTemplates) {
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
          inputs.add(input.toString().replace("Input", "SelectTraceIdsFromServiceSpan"));
        }
        return "FlatMapServicesToInputs{" + inputs + "}";
      }
    }
  }

  final Factory factory;
  final PreparedStatement preparedStatement;
  final Input input;

  SelectTraceIdsFromServiceSpan(Factory factory, PreparedStatement preparedStatement, Input input) {
    this.factory = factory;
    this.preparedStatement = preparedStatement;
    this.input = input;
  }

  @Override protected CompletionStage<AsyncResultSet> newCompletionStage() {
    int i = 0;
    BoundStatementBuilder bound = preparedStatement.boundStatementBuilder()
      .setString(i++, input.service())
      .setString(i++, input.span())
      .setInt(i++, input.bucket())
      .setUuid(i++, input.start_ts())
      .setUuid(i++, input.end_ts());

    if (input.start_duration() != null) {
      bound.setLong(i++, input.start_duration());
      bound.setLong(i++, input.end_duration());
    }

    bound
      .setInt(i, input.limit_())
      .setPageSize(input.limit_());

    return factory.session.executeAsync(bound.build());
  }

  @Override public AsyncResultSet map(AsyncResultSet input) {
    return input;
  }

  @Override public String toString() {
    return input.toString().replace("Input", "SelectTraceIdsFromServiceSpan");
  }

  @Override public SelectTraceIdsFromServiceSpan clone() {
    return new SelectTraceIdsFromServiceSpan(factory, preparedStatement, input);
  }
}
