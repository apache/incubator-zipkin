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
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.stream.IntStream;
import zipkin2.internal.DelayLimiter;
import zipkin2.storage.QueryRequest;
import zipkin2.storage.cassandra.internal.call.DeduplicatingInsert;

import static java.util.concurrent.TimeUnit.SECONDS;
import static java.util.stream.Collectors.toList;

/**
 * Inserts index rows into a Cassandra table. This skips entries that don't improve results based on
 * {@link QueryRequest#endTs()} and {@link QueryRequest#lookback()}. For example, it doesn't insert
 * rows that only vary on timestamp and exist between timestamps of existing rows.
 */
final class IndexTraceId extends DeduplicatingInsert<IndexTraceId.Input> {
  /**
   * Used to avoid hot spots when writing indexes used to query by service name or annotation.
   *
   * <p>This controls the amount of buckets, or partitions writes to {@code service_name_index}
   * and {@code annotations_index}. This must be the same for all query servers, and has
   * historically always been 10.
   *
   * <p>See https://github.com/openzipkin/zipkin/issues/623 for further explanation
   */
  static final int BUCKET_COUNT = 10;
  static final List<Integer> BUCKETS = IntStream.range(0, BUCKET_COUNT).boxed().collect(toList());

  @AutoValue abstract static class Input {
    static Input create(String partitionKey, long timestamp, long traceId) {
      return new AutoValue_IndexTraceId_Input(partitionKey, timestamp, traceId);
    }

    abstract String partitionKey(); // ends up as a partition key, ignoring bucketing

    abstract long ts(); // microseconds at millis precision

    abstract long trace_id(); // clustering key
  }

  static abstract class Factory extends DeduplicatingInsert.Factory<Input> {
    final CqlSession session;
    final TraceIdIndexer.Factory indexerFactory;
    final PreparedStatement preparedStatement;

    Factory(String statement, CassandraStorage storage, int indexTtl) {
      super(SECONDS.toMillis(storage.indexCacheTtl), storage.indexCacheMax);
      session = storage.session();
      indexerFactory = new TraceIdIndexer.Factory(statement, SECONDS.toNanos(storage.indexCacheTtl),
        storage.indexCacheMax);
      this.preparedStatement = session.prepare(
        indexTtl > 0 ? statement + " USING TTL " + indexTtl : statement);
    }

    abstract void bindPartitionKey(BoundStatementBuilder bound, String partitionKey);

    @Override protected IndexTraceId newCall(Input input) {
      return new IndexTraceId(this, delayLimiter, input);
    }

    TraceIdIndexer newIndexer() {
      return indexerFactory.newIndexer();
    }

    @Override public void clear() {
      super.clear();
      indexerFactory.clear();
    }
  }

  final Factory factory;

  IndexTraceId(Factory factory, DelayLimiter<Input> delayLimiter, Input input) {
    super(delayLimiter, input);
    this.factory = factory;
  }

  @Override protected CompletionStage<AsyncResultSet> newCompletionStage() {
    BoundStatementBuilder bound = factory.preparedStatement.boundStatementBuilder()
      .setBytesUnsafe(0, TimestampCodec.serialize(input.ts()))
      .setLong(1, input.trace_id());
    factory.bindPartitionKey(bound, input.partitionKey());
    return factory.session.executeAsync(bound.build());
  }

  @Override public String toString() {
    return input.toString().replace("Input", factory.getClass().getSimpleName());
  }

  @Override public IndexTraceId clone() {
    return new IndexTraceId(factory, delayLimiter, input);
  }
}
