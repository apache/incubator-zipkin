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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import zipkin2.Annotation;
import zipkin2.Call;
import zipkin2.Endpoint;
import zipkin2.Span;
import zipkin2.internal.Nullable;
import zipkin2.storage.cassandra.internal.call.ResultSetFutureCall;

import static zipkin2.storage.cassandra.Schema.TABLE_SPAN;

final class InsertSpan extends ResultSetFutureCall<Void> {
  @AutoValue abstract static class Input {
    abstract UUID ts_uuid();

    @Nullable abstract String trace_id_high();

    abstract String trace_id();

    @Nullable abstract String parent_id();

    abstract String id();

    @Nullable abstract String kind();

    @Nullable abstract String span();

    abstract long ts();

    abstract long duration();

    @Nullable abstract Endpoint l_ep();

    @Nullable abstract Endpoint r_ep();

    abstract List<Annotation> annotations();

    abstract Map<String, String> tags();

    @Nullable abstract String annotation_query();

    abstract boolean shared();

    abstract boolean debug();
  }

  static final class Factory {
    final CqlSession session;
    final PreparedStatement preparedStatement;
    final boolean strictTraceId, searchEnabled;

    Factory(CqlSession session, boolean strictTraceId, boolean searchEnabled) {
      this.session = session;
      String insertQuery = "INSERT INTO " + TABLE_SPAN
        + " (trace_id,trace_id_high,ts_uuid,parent_id,id,kind,span,ts,duration,l_ep,r_ep,annotations,tags,shared,debug)"
        + " VALUES (:trace_id,:trace_id_high,:ts_uuid,:parent_id,:id,:kind,:span,:ts,:duration,:l_ep,:r_ep,:annotations,:tags,:shared,:debug)";

      if (searchEnabled) {
        insertQuery = insertQuery.replace(",debug)", ",debug, l_service, annotation_query)");
        insertQuery = insertQuery.replace(",:debug)", ",:debug, :l_service, :annotation_query)");
      }

      this.preparedStatement = session.prepare(insertQuery);
      this.strictTraceId = strictTraceId;
      this.searchEnabled = searchEnabled;
    }

    Input newInput(Span span, UUID ts_uuid) {
      boolean traceIdHigh = !strictTraceId && span.traceId().length() == 32;
      String annotation_query = searchEnabled ? CassandraUtil.annotationQuery(span) : null;
      return new AutoValue_InsertSpan_Input(
        ts_uuid,
        traceIdHigh ? span.traceId().substring(0, 16) : null,
        traceIdHigh ? span.traceId().substring(16) : span.traceId(),
        span.parentId(),
        span.id(),
        span.kind() != null ? span.kind().name() : null,
        span.name(),
        span.timestampAsLong(),
        span.durationAsLong(),
        span.localEndpoint(),
        span.remoteEndpoint(),
        span.annotations(),
        span.tags(),
        annotation_query,
        Boolean.TRUE.equals(span.debug()),
        Boolean.TRUE.equals(span.shared()));
    }

    Call<Void> create(Input span) {
      return new InsertSpan(this, span);
    }
  }

  final Factory factory;
  final Input input;

  InsertSpan(Factory factory, Input input) {
    this.factory = factory;
    this.input = input;
  }

  /**
   * TLDR: we are guarding against setting null, as doing so implies tombstones. We are dodging setX
   * to keep code simpler than other alternatives described below.
   *
   * <p>If there's consistently 8 tombstones (nulls) per row, then we'll only need 125 spans in a
   * trace (rows in a partition) to trigger the `tombstone_warn_threshold warnings being logged in
   * the C* nodes. And if we go to 12500 spans in a trace then that whole trace partition would
   * become unreadable. Cassandra warns at a 1000 tombstones in any query, and fails on 100000
   * tombstones.
   *
   * <p>There's also a small question about disk usage efficiency. Each tombstone is a cell name
   * and basically empty cell value entry stored on disk. Given that the cells are, apart from tags
   * and annotations, generally very small then this could be proportionally an unnecessary waste of
   * disk.
   *
   * <p>To avoid this relying upon a number of variant prepared statements for inserting a span is
   * the normal practice.
   *
   * <p>Another popular practice is to insert those potentially null columns as separate statements
   * (and optionally put them together into UNLOGGED batches). This works as multiple writes to the
   * same partition has little overhead, and here we're not worried about lack of isolation between
   * those writes, as the write is asynchronous anyway. An example of this approach is in the
   * cassandra-reaper project here: https://github.com/thelastpickle/cassandra-reaper/blob/master/src/server/src/main/java/io/cassandrareaper/storage/CassandraStorage.java#L622-L642
   */
  @Override protected CompletionStage<AsyncResultSet> newCompletionStage() {
    BoundStatementBuilder bound = factory.preparedStatement.boundStatementBuilder()
      .setUuid("ts_uuid", input.ts_uuid())
      .setString("trace_id", input.trace_id())
      .setString("id", input.id());

    // Don't set null as we don't want to add tombstones
    if (null != input.trace_id_high()) bound.setString("trace_id_high", input.trace_id_high());
    if (null != input.parent_id()) bound.setString("parent_id", input.parent_id());
    if (null != input.kind()) bound.setString("kind", input.kind());
    if (null != input.span()) bound.setString("span", input.span());
    if (0L != input.ts()) bound.setLong("ts", input.ts());
    if (0L != input.duration()) bound.setLong("duration", input.duration());
    if (null != input.l_ep()) bound.set("l_ep", input.l_ep(), Endpoint.class);
    if (null != input.r_ep()) bound.set("r_ep", input.r_ep(), Endpoint.class);
    if (!input.annotations().isEmpty()) {
      bound.setList("annotations", input.annotations(), Annotation.class);
    }
    if (!input.tags().isEmpty()) bound.setMap("tags", input.tags(), String.class, String.class);
    if (input.shared()) bound.setBoolean("shared", true);
    if (input.debug()) bound.setBoolean("debug", true);

    if (factory.searchEnabled) {
      if (null != input.l_ep()) bound.setString("l_service", input.l_ep().serviceName());
      if (null != input.annotation_query()) {
        bound.setString("annotation_query", input.annotation_query());
      }
    }
    return factory.session.executeAsync(bound.build());
  }

  @Override public Void map(AsyncResultSet input) {
    return null;
  }

  @Override public String toString() {
    return input.toString().replace("Input", "InsertSpan");
  }

  @Override public InsertSpan clone() {
    return new InsertSpan(factory, input);
  }
}
