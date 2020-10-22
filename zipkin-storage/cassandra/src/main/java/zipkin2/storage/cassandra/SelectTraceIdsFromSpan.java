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
import com.datastax.oss.driver.api.core.cql.Row;
import com.google.auto.value.AutoValue;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import zipkin2.Call;
import zipkin2.internal.Nullable;
import zipkin2.storage.cassandra.CassandraSpanStore.TimestampRange;
import zipkin2.storage.cassandra.internal.call.AccumulateAllResults;
import zipkin2.storage.cassandra.internal.call.ResultSetFutureCall;

import static zipkin2.storage.cassandra.Schema.TABLE_SPAN;

/**
 * Selects from the {@link Schema#TABLE_SPAN} using data in the partition key or SASI indexes.
 *
 * <p>Note: While queries here use "ALLOW FILTERING", they do so within a SASI clause, and only
 * return (traceId, timestamp) tuples. This means the entire spans table is not scanned, unless the
 * time range implies that.
 *
 * <p>The spans table is sorted descending by timestamp. When a query includes only a time range,
 * the first N rows are already in the correct order. However, the cardinality of rows is a function
 * of span count, not trace count. This implies an over-fetch function based on average span count
 * per trace in order to achieve N distinct trace IDs. For example if there are 3 spans per trace,
 * and over-fetch function of 3 * intended limit will work. See {@link
 * CassandraStorage#indexFetchMultiplier} for an associated parameter.
 */
final class SelectTraceIdsFromSpan extends ResultSetFutureCall<AsyncResultSet> {
  @AutoValue abstract static class Input {
    @Nullable abstract String l_service();

    @Nullable abstract String annotation_query();

    abstract UUID start_ts();

    abstract UUID end_ts();

    abstract int limit_();
  }

  static final class Factory {
    final CqlSession session;
    final PreparedStatement withAnnotationQuery, withServiceAndAnnotationQuery;

    Factory(CqlSession session) {
      this.session = session;
      String querySuffix = "annotation_query LIKE ?"
        + " AND ts_uuid>=?"
        + " AND ts_uuid<=?"
        + " LIMIT ?"
        + " ALLOW FILTERING";
      this.withAnnotationQuery = session.prepare("SELECT trace_id,ts"
        + " FROM " + TABLE_SPAN
        + " WHERE " + querySuffix);
      this.withServiceAndAnnotationQuery = session.prepare("SELECT trace_id,ts"
        + " FROM " + TABLE_SPAN
        + " WHERE l_service=:l_service"
        + " AND " + querySuffix);
    }

    Call<Map<String, Long>> newCall(
      @Nullable String serviceName,
      String annotationKey,
      TimestampRange timestampRange,
      int limit) {
      Input input = new AutoValue_SelectTraceIdsFromSpan_Input(
        serviceName,
        annotationKey,
        timestampRange.startUUID,
        timestampRange.endUUID,
        limit);
      PreparedStatement preparedStatement =
        serviceName != null ? withServiceAndAnnotationQuery : withAnnotationQuery;
      return new SelectTraceIdsFromSpan(this, preparedStatement, input)
        .flatMap(AccumulateTraceIdTsLong.get());
    }
  }

  final Factory factory;
  final PreparedStatement preparedStatement;
  final Input input;

  SelectTraceIdsFromSpan(Factory factory, PreparedStatement preparedStatement, Input input) {
    this.factory = factory;
    this.preparedStatement = preparedStatement;
    this.input = input;
  }

  @Override protected CompletionStage<AsyncResultSet> newCompletionStage() {
    BoundStatementBuilder bound = preparedStatement.boundStatementBuilder();
    int i = 0;
    if (input.l_service() != null) bound.setString(i++, input.l_service());
    if (input.annotation_query() != null) {
      bound.setString(i++, input.annotation_query());
    } else {
      throw new IllegalArgumentException(input.toString());
    }
    bound
      .setUuid(i++, input.start_ts())
      .setUuid(i++, input.end_ts())
      .setInt(i, input.limit_())
      .setPageSize(input.limit_());
    return factory.session.executeAsync(bound.build());
  }

  @Override public AsyncResultSet map(AsyncResultSet input) {
    return input;
  }

  @Override public SelectTraceIdsFromSpan clone() {
    return new SelectTraceIdsFromSpan(factory, preparedStatement, input);
  }

  @Override public String toString() {
    return input.toString().replace("Input", "SelectTraceIdsFromSpan");
  }

  static final class AccumulateTraceIdTsLong extends AccumulateAllResults<Map<String, Long>> {
    static final AccumulateAllResults<Map<String, Long>> INSTANCE = new AccumulateTraceIdTsLong();

    static AccumulateAllResults<Map<String, Long>> get() {
      return INSTANCE;
    }

    @Override protected Supplier<Map<String, Long>> supplier() {
      return LinkedHashMap::new; // because results are not distinct
    }

    @Override protected BiConsumer<Row, Map<String, Long>> accumulator() {
      return (row, result) -> {
        if (row.isNull(1)) return; // no timestamp
        result.put(row.getString(0), row.getLong(1));
      };
    }

    @Override public String toString() {
      return "AccumulateTraceIdTsLong{}";
    }
  }
}
