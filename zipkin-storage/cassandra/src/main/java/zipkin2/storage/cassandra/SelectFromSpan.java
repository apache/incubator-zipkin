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
import com.datastax.oss.driver.api.core.cql.Row;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import zipkin2.Annotation;
import zipkin2.Call;
import zipkin2.Endpoint;
import zipkin2.Span;
import zipkin2.internal.FilterTraces;
import zipkin2.internal.Nullable;
import zipkin2.storage.GroupByTraceId;
import zipkin2.storage.QueryRequest;
import zipkin2.storage.StrictTraceId;
import zipkin2.storage.cassandra.internal.call.AccumulateAllResults;
import zipkin2.storage.cassandra.internal.call.ResultSetFutureCall;

import static zipkin2.storage.cassandra.Schema.TABLE_SPAN;

final class SelectFromSpan extends ResultSetFutureCall<AsyncResultSet> {
  static final class Factory {
    final CqlSession session;
    final PreparedStatement preparedStatement;
    final Call.Mapper<List<Span>, List<List<Span>>> groupByTraceId;
    final boolean strictTraceId;
    final int maxTraceCols;

    Factory(CqlSession session, boolean strictTraceId, int maxTraceCols) {
      this.session = session;
      this.preparedStatement = session.prepare(
        "SELECT trace_id_high,trace_id,parent_id,id,kind,span,ts,duration,l_ep,r_ep,annotations,tags,shared,debug"
          + " FROM " + TABLE_SPAN
          + " WHERE trace_id IN ?"
          + " LIMIT ?");
      this.strictTraceId = strictTraceId;
      this.maxTraceCols = maxTraceCols;
      this.groupByTraceId = GroupByTraceId.create(strictTraceId);
    }

    Call<List<Span>> newCall(String hexTraceId) {
      // Unless we are strict, truncate the trace ID to 64bit (encoded as 16 characters)
      Set<String> traceIds;
      if (!strictTraceId && hexTraceId.length() == 32) {
        traceIds = new LinkedHashSet<>();
        traceIds.add(hexTraceId);
        traceIds.add(hexTraceId.substring(16));
      } else {
        traceIds = Collections.singleton(hexTraceId);
      }

      Call<List<Span>> result =
        new SelectFromSpan(this, traceIds, maxTraceCols).flatMap(READ_SPANS);
      return strictTraceId ? result.map(StrictTraceId.filterSpans(hexTraceId)) : result;
    }

    Call<List<List<Span>>> newCall(Iterable<String> traceIds) {
      Set<String> normalizedTraceIds = new LinkedHashSet<>();
      for (String traceId : traceIds) {
        // make sure we have a 16 or 32 character trace ID
        traceId = Span.normalizeTraceId(traceId);
        // Unless we are strict, truncate the trace ID to 64bit (encoded as 16 characters)
        if (!strictTraceId && traceId.length() == 32) traceId = traceId.substring(16);
        normalizedTraceIds.add(traceId);
      }

      if (normalizedTraceIds.isEmpty()) return Call.emptyList();
      Call<List<List<Span>>> result = new SelectFromSpan(this, normalizedTraceIds, maxTraceCols)
        .flatMap(READ_SPANS)
        .map(groupByTraceId);
      return strictTraceId ? result.map(StrictTraceId.filterTraces(normalizedTraceIds)) : result;
    }

    FlatMapper<Set<String>, List<List<Span>>> newFlatMapper(QueryRequest request) {
      return new SelectSpansByTraceIds(this, request);
    }
  }

  final Factory factory;
  final Set<String> trace_id;
  final int limit_;

  /** @param limit_ amount of spans per trace is almost always larger than trace IDs */
  SelectFromSpan(Factory factory, Set<String> trace_id, int limit_) {
    this.factory = factory;
    this.trace_id = trace_id;
    this.limit_ = limit_;
  }

  @Override protected CompletionStage<AsyncResultSet> newCompletionStage() {
    return factory.session.executeAsync(factory.preparedStatement.boundStatementBuilder()
      // Switched Set to List which is higher overhead, as have to copy into it, but avoids this:
      // com.datastax.oss.driver.api.core.type.codec.CodecNotFoundException: Codec not found for requested operation: [List(TEXT, not frozen) <-> java.util.Set<java.lang.String>]
      .setList(0, new ArrayList<>(trace_id), String.class)
      .setInt(1, limit_).build());
  }

  @Override public AsyncResultSet map(AsyncResultSet input) {
    return input;
  }

  @Override public String toString() {
    return "SelectFromSpan{trace_id=" + trace_id + ", limit_=" + limit_ + "}";
  }

  @Override public SelectFromSpan clone() {
    return new SelectFromSpan(factory, trace_id, limit_);
  }

  static final class SelectSpansByTraceIds implements FlatMapper<Set<String>, List<List<Span>>> {
    final Factory factory;
    final int limit;
    @Nullable final Call.Mapper<List<List<Span>>, List<List<Span>>> filter;

    SelectSpansByTraceIds(Factory factory, QueryRequest request) {
      this.factory = factory;
      this.limit = request.limit();
      // Cassandra always looks up traces by 64-bit trace ID, so we have to unconditionally filter
      // when strict trace ID is enabled.
      this.filter = factory.strictTraceId ? FilterTraces.create(request) : null;
    }

    @Override public Call<List<List<Span>>> map(Set<String> input) {
      if (input.isEmpty()) return Call.emptyList();
      Set<String> traceIds;
      if (input.size() > limit) {
        traceIds = new LinkedHashSet<>();
        Iterator<String> iterator = input.iterator();
        for (int i = 0; i < limit; i++) {
          traceIds.add(iterator.next());
        }
      } else {
        traceIds = input;
      }
      Call<List<List<Span>>> result = new SelectFromSpan(factory, traceIds, factory.maxTraceCols)
        .flatMap(READ_SPANS)
        .map(factory.groupByTraceId);
      return filter != null ? result.map(filter) : result;
    }

    @Override public String toString() {
      return "SelectSpansByTraceIds{limit=" + limit + "}";
    }
  }

  static final AccumulateAllResults<List<Span>> READ_SPANS = new ReadSpans();

  static final class ReadSpans extends AccumulateAllResults<List<Span>> {

    @Override protected Supplier<List<Span>> supplier() {
      return ArrayList::new;
    }

    @Override protected BiConsumer<Row, List<Span>> accumulator() {
      return (row, result) -> {
        String traceId = row.getString("trace_id");
        String traceIdHigh = row.getString("trace_id_high");
        if (traceIdHigh != null) traceId = traceIdHigh + traceId;
        Span.Builder builder = Span.newBuilder()
          .traceId(traceId)
          .parentId(row.getString("parent_id"))
          .id(row.getString("id"))
          .name(row.getString("span"));

        if (!row.isNull("ts")) builder.timestamp(row.getLong("ts"));
        if (!row.isNull("duration")) builder.duration(row.getLong("duration"));

        if (!row.isNull("kind")) {
          try {
            builder.kind(Span.Kind.valueOf(row.getString("kind")));
          } catch (IllegalArgumentException ignored) {
            // EmptyCatch ignored
          }
        }
        if (!row.isNull("l_ep")) {
          builder.localEndpoint(row.get("l_ep", Endpoint.class));
        }
        if (!row.isNull("r_ep")) {
          builder.remoteEndpoint(row.get("r_ep", Endpoint.class));
        }

        if (!row.isNull("shared")) builder.shared(row.getBoolean("shared"));
        if (!row.isNull("debug")) builder.shared(row.getBoolean("debug"));

        for (Annotation annotation : row.getList("annotations", Annotation.class)) {
          builder.addAnnotation(annotation.timestamp(), annotation.value());
        }
        for (Map.Entry<String, String> tag :
          row.getMap("tags", String.class, String.class).entrySet()) {
          builder.putTag(tag.getKey(), tag.getValue());
        }
        result.add(builder.build());
      };
    }

    @Override public String toString() {
      return "ReadSpans{}";
    }
  }
}
