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
import com.datastax.oss.driver.api.core.cql.PreparedStatement;
import com.datastax.oss.driver.api.core.cql.Row;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import zipkin2.Call;
import zipkin2.Span;
import zipkin2.internal.FilterTraces;
import zipkin2.internal.HexCodec;
import zipkin2.internal.Nullable;
import zipkin2.internal.ReadBuffer;
import zipkin2.internal.V1ThriftSpanReader;
import zipkin2.storage.GroupByTraceId;
import zipkin2.storage.QueryRequest;
import zipkin2.storage.StrictTraceId;
import zipkin2.storage.cassandra.internal.call.AccumulateAllResults;
import zipkin2.storage.cassandra.internal.call.ResultSetFutureCall;
import zipkin2.v1.V1Span;
import zipkin2.v1.V1SpanConverter;

import static java.util.Collections.singletonList;
import static zipkin2.storage.cassandra.v1.Tables.TRACES;

final class SelectFromTraces extends ResultSetFutureCall<AsyncResultSet> {
  static final class Factory {
    final CqlSession session;
    final PreparedStatement preparedStatement;
    final Call.Mapper<List<Span>, List<List<Span>>> groupByTraceId;
    final int maxTraceCols; // amount of spans per trace is almost always larger than trace IDs
    final boolean strictTraceId;

    Factory(CqlSession session, boolean strictTraceId, int maxTraceCols) {
      this.session = session;
      this.preparedStatement = session.prepare("SELECT trace_id,span"
        + " FROM " + TRACES
        + " WHERE trace_id IN ?"
        + " LIMIT ?");
      this.maxTraceCols = maxTraceCols;
      this.strictTraceId = strictTraceId;
      this.groupByTraceId = GroupByTraceId.create(strictTraceId);
    }

    Call<List<Span>> newCall(String hexTraceId) {
      long traceId = HexCodec.lowerHexToUnsignedLong(hexTraceId);
      Call<List<Span>> result = new SelectFromTraces(this, singletonList(traceId), maxTraceCols)
        .flatMap(DecodeAndConvertSpans.get());
      return strictTraceId ? result.map(StrictTraceId.filterSpans(hexTraceId)) : result;
    }

    Call<List<List<Span>>> newCall(Iterable<String> traceIds) {
      Set<Long> longTraceIds = new LinkedHashSet<>();
      Set<String> normalizedTraceIds = new LinkedHashSet<>();
      for (String traceId : traceIds) {
        traceId = Span.normalizeTraceId(traceId);
        normalizedTraceIds.add(traceId);
        longTraceIds.add(HexCodec.lowerHexToUnsignedLong(traceId));
      }

      if (normalizedTraceIds.isEmpty()) return Call.emptyList();

      Call<List<List<Span>>> result =
        new SelectFromTraces(this, new ArrayList<>(longTraceIds), maxTraceCols)
          .flatMap(DecodeAndConvertSpans.get())
          .map(groupByTraceId);
      return strictTraceId ? result.map(StrictTraceId.filterTraces(normalizedTraceIds)) : result;
    }

    FlatMapper<Set<Long>, List<List<Span>>> newFlatMapper(QueryRequest request) {
      return new SelectTracesByIds(this, request);
    }
  }

  final Factory factory;
  // Switched Set to List which is higher overhead, as have to copy into it, but avoids this:
  // com.datastax.oss.driver.api.core.type.codec.CodecNotFoundException: Codec not found for requested operation: [List(BIGINT, not frozen) <-> java.util.Set<java.lang.Long>]
  final List<Long> trace_id;
  final int limit_;

  SelectFromTraces(Factory factory, List<Long> trace_id, int limit_) {
    this.factory = factory;
    this.trace_id = trace_id;
    this.limit_ = limit_;
  }

  @Override protected CompletionStage<AsyncResultSet> newCompletionStage() {
    return factory.session.executeAsync(factory.preparedStatement.boundStatementBuilder()
      .setList(0, trace_id, Long.class)
      .setInt(1, limit_).build());
  }

  @Override public AsyncResultSet map(AsyncResultSet input) {
    return input;
  }

  @Override public String toString() {
    return "SelectFromTraces{trace_id=" + trace_id + ", limit_=" + limit_ + "}";
  }

  @Override public SelectFromTraces clone() {
    return new SelectFromTraces(factory, trace_id, limit_);
  }

  static final class SelectTracesByIds implements FlatMapper<Set<Long>, List<List<Span>>> {
    final Factory factory;
    final int limit;
    @Nullable final Call.Mapper<List<List<Span>>, List<List<Span>>> filter;

    SelectTracesByIds(Factory factory, QueryRequest request) {
      this.factory = factory;
      this.limit = request.limit();
      // Cassandra always looks up traces by 64-bit trace ID, so we have to unconditionally filter
      // when strict trace ID is enabled.
      this.filter = factory.strictTraceId ? FilterTraces.create(request) : null;
    }

    @Override public Call<List<List<Span>>> map(Set<Long> input) {
      if (input.isEmpty()) return Call.emptyList();
      Set<Long> traceIds;
      if (input.size() > limit) {
        traceIds = new LinkedHashSet<>();
        Iterator<Long> iterator = input.iterator();
        for (int i = 0; i < limit; i++) {
          traceIds.add(iterator.next());
        }
      } else {
        traceIds = input;
      }
      Call<List<List<Span>>> result =
        new SelectFromTraces(factory, new ArrayList<>(traceIds), factory.maxTraceCols)
          .flatMap(DecodeAndConvertSpans.get())
          .map(factory.groupByTraceId);
      return filter != null ? result.map(filter) : result;
    }

    @Override public String toString() {
      return "SelectTracesByIds{limit=" + limit + "}";
    }
  }

  static final class DecodeAndConvertSpans extends AccumulateAllResults<List<Span>> {
    static final AccumulateAllResults<List<Span>> INSTANCE = new DecodeAndConvertSpans();

    public static AccumulateAllResults<List<Span>> get() {
      return INSTANCE;
    }

    @Override protected Supplier<List<Span>> supplier() {
      return ArrayList::new;
    }

    @Override protected BiConsumer<Row, List<Span>> accumulator() {
      return (row, result) -> {
        V1ThriftSpanReader reader = V1ThriftSpanReader.create();
        V1SpanConverter converter = V1SpanConverter.create();
        V1Span read = reader.read(ReadBuffer.wrapUnsafe(row.getBytesUnsafe(1)));
        converter.convert(read, result);
      };
    }

    @Override public String toString() {
      return "DecodeAndConvertSpans{}";
    }
  }
}
