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

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.AsyncResultSet;
import com.datastax.oss.driver.api.core.cql.BoundStatementBuilder;
import com.datastax.oss.driver.api.core.cql.PreparedStatement;
import com.google.auto.value.AutoValue;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import zipkin2.Call;
import zipkin2.storage.cassandra.internal.call.ResultSetFutureCall;

final class SelectTraceIdIndex<K> extends ResultSetFutureCall<AsyncResultSet> {
  @AutoValue abstract static class Input<K> {
    static <K> Input<K> create(K partitionKey, long endTs, long lookback, int limit) {
      long startTs = Math.max(endTs - lookback, 0); // >= 1970
      return new AutoValue_SelectTraceIdIndex_Input<>(partitionKey, startTs, endTs, limit);
    }

    Input<K> withPartitionKey(K partitionKey) {
      return new AutoValue_SelectTraceIdIndex_Input<>(partitionKey, start_ts(), end_ts(),
        limit_());
    }

    abstract K partitionKey(); // ends up as a partition key, ignoring bucketing

    abstract long start_ts();

    abstract long end_ts();

    abstract int limit_();
  }

  static abstract class Factory<K> {
    final CqlSession session;
    final String table, partitionKeyColumn;
    final PreparedStatement preparedStatement;
    final int firstMarkerIndex;

    Factory(CqlSession session, String table, String partitionKeyColumn, int partitionKeyCount) {
      this.session = session;
      this.table = table;
      this.partitionKeyColumn = partitionKeyColumn;
      // Tables queried hare defined CLUSTERING ORDER BY "ts".
      // We don't use orderBy in queries per: https://www.datastax.com/blog/we-shall-have-order
      // Sorting is done client-side via sortTraceIdsByDescTimestamp()
      preparedStatement = session.prepare(selectStatement(table, partitionKeyColumn)
        + " AND ts>=?"
        + " AND ts<=?"
        + " LIMIT ?");
      firstMarkerIndex = partitionKeyCount;
    }

    String selectStatement(String table, String partitionKeyColumn) {
      return "SELECT trace_id,ts"
        + " FROM " + table
        + " WHERE " + partitionKeyColumn + "=?";
    }

    abstract void bindPartitionKey(BoundStatementBuilder bound, K partitionKey);

    Call<Set<Pair>> newCall(Input<K> input) {
      return new SelectTraceIdIndex<>(this, input).flatMap(AccumulateTraceIdTsLong.get());
    }
  }

  final Factory<K> factory;
  final Input<K> input;

  SelectTraceIdIndex(Factory<K> factory, Input<K> input) {
    this.factory = factory;
    this.input = input;
  }

  @Override protected CompletionStage<AsyncResultSet> newCompletionStage() {
    BoundStatementBuilder bound = factory.preparedStatement.boundStatementBuilder();
    factory.bindPartitionKey(bound, input.partitionKey());

    int i = factory.firstMarkerIndex;
    return factory.session.executeAsync(bound
      .setBytesUnsafe(i, TimestampCodec.serialize(input.start_ts()))
      .setBytesUnsafe(i + 1, TimestampCodec.serialize(input.end_ts()))
      .setInt(i + 2, input.limit_())
      .setPageSize(input.limit_()).build());
  }

  @Override public AsyncResultSet map(AsyncResultSet input) {
    return input;
  }

  @Override public String toString() {
    return "SelectTraceIdIndex{table=" + factory.table + ", "
      + factory.partitionKeyColumn + "=" + input.partitionKey()
      + "}";
  }

  @Override public SelectTraceIdIndex<K> clone() {
    return new SelectTraceIdIndex<>(factory, input);
  }
}
