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
import java.nio.ByteBuffer;
import java.util.AbstractMap.SimpleImmutableEntry;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import zipkin2.Annotation;
import zipkin2.Call;
import zipkin2.Span;
import zipkin2.internal.AggregateCall;
import zipkin2.internal.HexCodec;
import zipkin2.internal.Nullable;
import zipkin2.internal.V1ThriftSpanWriter;
import zipkin2.storage.SpanConsumer;
import zipkin2.storage.cassandra.internal.call.InsertEntry;
import zipkin2.v1.V1Span;
import zipkin2.v1.V2SpanConverter;

import static zipkin2.storage.cassandra.v1.CassandraUtil.annotationKeys;

final class CassandraSpanConsumer implements SpanConsumer {
  final InsertTrace.Factory insertTrace;
  final boolean searchEnabled;
  final Set<String> autocompleteKeys;

  // Everything below here is client-side indexing for QueryRequest and null when search is disabled
  @Nullable final InsertServiceName.Factory insertServiceName;
  @Nullable final InsertEntry.Factory insertRemoteServiceName;
  @Nullable final InsertEntry.Factory insertSpanName;
  @Nullable final InsertEntry.Factory insertAutocompleteValue;
  @Nullable final IndexTraceIdByServiceName indexTraceIdByServiceName;
  @Nullable final IndexTraceIdByRemoteServiceName indexTraceIdByRemoteServiceName;
  @Nullable final IndexTraceIdBySpanName indexTraceIdBySpanName;
  @Nullable final IndexTraceIdByAnnotation indexTraceIdByAnnotation;

  void clear() {
    if (insertServiceName != null) insertServiceName.clear();
    if (insertRemoteServiceName != null) insertRemoteServiceName.clear();
    if (insertSpanName != null) insertSpanName.clear();
    if (insertAutocompleteValue != null) insertAutocompleteValue.clear();
    if (indexTraceIdByServiceName != null) indexTraceIdByServiceName.clear();
    if (indexTraceIdByRemoteServiceName != null) indexTraceIdByRemoteServiceName.clear();
    if (indexTraceIdBySpanName != null) indexTraceIdBySpanName.clear();
    if (indexTraceIdByAnnotation != null) indexTraceIdByAnnotation.clear();
  }

  CassandraSpanConsumer(CassandraStorage storage) {
    CqlSession session = storage.session();
    Schema.Metadata metadata = storage.metadata();
    searchEnabled = storage.searchEnabled;
    autocompleteKeys = new LinkedHashSet<>(storage.autocompleteKeys);
    int spanTtl = metadata.hasDefaultTtl ? 0 : storage.spanTtl;

    insertTrace = new InsertTrace.Factory(session, metadata, spanTtl);

    if (!searchEnabled) {
      insertServiceName = null;
      insertRemoteServiceName = null;
      insertSpanName = null;
      insertAutocompleteValue = null;

      indexTraceIdByServiceName = null;
      indexTraceIdByRemoteServiceName = null;
      indexTraceIdBySpanName = null;
      indexTraceIdByAnnotation = null;
      return;
    }

    int autocompleteTtl = storage.autocompleteTtl;
    int autocompleteCardinality = storage.autocompleteCardinality;
    int indexTtl = metadata.hasDefaultTtl ? 0 : storage.indexTtl;

    insertServiceName = new InsertServiceName.Factory(storage, indexTtl);
    indexTraceIdByServiceName = new IndexTraceIdByServiceName(storage, indexTtl);
    if (metadata.hasRemoteService) {
      insertRemoteServiceName = new InsertEntry.Factory(
        "INSERT INTO " + Tables.REMOTE_SERVICE_NAMES
          + " (service_name, remote_service_name) VALUES (?,?)",
        session, autocompleteTtl, autocompleteCardinality, indexTtl
      );
      indexTraceIdByRemoteServiceName = new IndexTraceIdByRemoteServiceName(storage, indexTtl);
    } else {
      insertRemoteServiceName = null;
      indexTraceIdByRemoteServiceName = null;
    }
    insertSpanName = new InsertEntry.Factory(
      // bucket is deprecated on this index
      "INSERT INTO " + Tables.SPAN_NAMES + " (service_name, span_name, bucket) VALUES (?,?, 0)",
      session, autocompleteTtl, autocompleteCardinality, indexTtl
    );
    indexTraceIdBySpanName = new IndexTraceIdBySpanName(storage, indexTtl);
    indexTraceIdByAnnotation = new IndexTraceIdByAnnotation(storage, indexTtl);
    if (metadata.hasAutocompleteTags && !storage.autocompleteKeys.isEmpty()) {
      insertAutocompleteValue = new InsertEntry.Factory(
        "INSERT INTO " + Tables.AUTOCOMPLETE_TAGS + " (key, value) VALUES (?,?)",
        session, autocompleteTtl, autocompleteCardinality, indexTtl
      );
    } else {
      insertAutocompleteValue = null;
    }
  }

  /**
   * This fans out into many requests, last count was 8 * spans.size. If any of these fail, the
   * returned future will fail. Most callers drop or log the result.
   */
  @Override public Call<Void> accept(List<Span> spans) {
    int spanCount = spans.size();
    if (spanCount == 0) return Call.create(null);

    V2SpanConverter converter = V2SpanConverter.create();
    V1ThriftSpanWriter encoder = new V1ThriftSpanWriter();

    Set<InsertTrace.Input> insertTraces = new LinkedHashSet<>();

    List<Call<Void>> calls = new ArrayList<>();
    for (int i = 0; i < spanCount; i++) {
      Span span = spans.get(i);
      V1Span v1Span = converter.convert(span);

      // trace records need an insertion timestamp, so derive one if not present.
      long ts_micro = span.timestampAsLong();
      if (ts_micro == 0L) ts_micro = guessTimestamp(span);

      insertTraces.add(
        insertTrace.newInput(v1Span, ByteBuffer.wrap(encoder.write(span)), ts_micro));
    }

    for (InsertTrace.Input insert : insertTraces) {
      calls.add(insertTrace.create(insert));
    }

    if (!searchEnabled) return AggregateCall.newVoidCall(calls);

    // Using set or other deduplication strategies helps avoid redundant writes.
    Set<String> insertServiceNames = new LinkedHashSet<>();
    Set<Map.Entry<String, String>> insertRemoteServiceNames = new LinkedHashSet<>();
    Set<Map.Entry<String, String>> insertSpanNames = new LinkedHashSet<>();
    Set<Map.Entry<String, String>> insertAutocompleteTags = new LinkedHashSet<>();
    TraceIdIndexer indexTraceIdByServiceNames = indexTraceIdByServiceName.newIndexer();
    TraceIdIndexer indexTraceIdByRemoteServiceNames = indexTraceIdByRemoteServiceName != null
      ? indexTraceIdByRemoteServiceName.newIndexer()
      : TraceIdIndexer.NOOP;
    TraceIdIndexer indexTraceIdBySpanNames = indexTraceIdBySpanName.newIndexer();
    TraceIdIndexer indexTraceIdByAnnotations = indexTraceIdByAnnotation.newIndexer();

    for (int i = 0; i < spanCount; i++) {
      Span span = spans.get(i);

      String serviceName = span.localServiceName();

      // All search parameters are partitioned on service name, so we skip populating choices when
      // this is missing.
      if (serviceName == null) continue;

      // Search in Cassandra v1 is implemented client-side, by adding rows corresponding to search
      // parameters. For example, a search for serviceName=app&spanName=bar will look for a
      // service_name_index row "app.foo" for the corresponding endTs and lookback. This has a few
      // implications:
      //
      // * timestamps need only millis precision (as query precision is millis)
      // * client-side indexing is pointless if there is no timestamp
      // * populating choices for indexes never added is also pointless
      //
      // We floor the micros timestamp to millis and skip populating choices on zero.
      long timestamp = 1000L * (span.timestampAsLong() / 1000L); // QueryRequest is precise to ms
      if (timestamp == 0L) continue;

      // Start with populating the name choices
      insertServiceNames.add(serviceName);

      String remoteServiceName = insertRemoteServiceName != null ? span.remoteServiceName() : null;
      if (remoteServiceName != null) {
        insertRemoteServiceNames.add(new SimpleImmutableEntry<>(serviceName, remoteServiceName));
      }

      String spanName = span.name();
      if (spanName != null) {
        insertSpanNames.add(new SimpleImmutableEntry<>(serviceName, spanName));
      }

      if (insertAutocompleteValue != null) {
        for (Map.Entry<String, String> entry : span.tags().entrySet()) {
          if (autocompleteKeys.contains(entry.getKey())) insertAutocompleteTags.add(entry);
        }
      }

      // Now, populate the index rows, noting indexes only consider lower 64-bits of the trace ID
      long traceId = HexCodec.lowerHexToUnsignedLong(span.traceId());

      indexTraceIdByServiceNames.add(IndexTraceId.Input.create(serviceName, timestamp, traceId));

      if (remoteServiceName != null) {
        String partitionKey = serviceName + "." + remoteServiceName;
        indexTraceIdByRemoteServiceNames.add(
          IndexTraceId.Input.create(partitionKey, timestamp, traceId));
      }

      if (spanName != null) {
        String partitionKey = serviceName + "." + spanName;
        indexTraceIdBySpanNames.add(IndexTraceId.Input.create(partitionKey, timestamp, traceId));
      }

      for (String partitionKey : annotationKeys(span)) {
        indexTraceIdByAnnotations.add(
          IndexTraceId.Input.create(partitionKey, timestamp, traceId));
      }
    }

    // We do actual inserts separately as the above loop could result in redundant data.
    // For example, multiple spans in the same trace would result in the same service name to trace
    // ID indexes. Regardless, the inputs to the cassandra CQL calls area also deduplicated in case
    // a different thread already wrote the same data meanwhile.
    for (String insert : insertServiceNames) {
      insertServiceName.maybeAdd(insert, calls);
    }
    for (Map.Entry<String, String> insert : insertRemoteServiceNames) {
      insertRemoteServiceName.maybeAdd(insert, calls);
    }
    for (Map.Entry<String, String> insert : insertSpanNames) {
      insertSpanName.maybeAdd(insert, calls);
    }
    for (Map.Entry<String, String> insert : insertAutocompleteTags) {
      insertAutocompleteValue.maybeAdd(insert, calls);
    }
    for (IndexTraceId.Input insert : indexTraceIdByServiceNames) {
      indexTraceIdByServiceName.maybeAdd(insert, calls);
    }
    for (IndexTraceId.Input insert : indexTraceIdByRemoteServiceNames) {
      indexTraceIdByRemoteServiceName.maybeAdd(insert, calls);
    }
    for (IndexTraceId.Input insert : indexTraceIdBySpanNames) {
      indexTraceIdBySpanName.maybeAdd(insert, calls);
    }
    for (IndexTraceId.Input insert : indexTraceIdByAnnotations) {
      indexTraceIdByAnnotation.maybeAdd(insert, calls);
    }
    return AggregateCall.newVoidCall(calls);
  }

  static long guessTimestamp(Span span) {
    assert 0L == span.timestampAsLong() : "method only for when span has no timestamp";
    for (Annotation annotation : span.annotations()) {
      if (0L < annotation.timestamp()) return annotation.timestamp();
    }
    return 0L; // return a timestamp that won't match a query
  }
}
