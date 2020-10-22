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
import com.datastax.oss.driver.api.core.ProtocolVersion;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zipkin2.Call;
import zipkin2.Call.FlatMapper;
import zipkin2.DependencyLink;
import zipkin2.Span;
import zipkin2.internal.AggregateCall;
import zipkin2.internal.Nullable;
import zipkin2.storage.QueryRequest;
import zipkin2.storage.ServiceAndSpanNames;
import zipkin2.storage.SpanStore;
import zipkin2.storage.Traces;
import zipkin2.storage.cassandra.v1.SelectTraceIdIndex.Input;

import static zipkin2.storage.cassandra.v1.CassandraUtil.sortTraceIdsByDescTimestamp;
import static zipkin2.storage.cassandra.v1.CassandraUtil.sortTraceIdsByDescTimestampMapper;
import static zipkin2.storage.cassandra.v1.Tables.SERVICE_REMOTE_SERVICE_NAME_INDEX;

public final class CassandraSpanStore implements SpanStore, Traces, ServiceAndSpanNames {
  static final Logger LOG = LoggerFactory.getLogger(CassandraSpanStore.class);

  final int maxTraceCols;
  final int indexFetchMultiplier;
  final boolean strictTraceId, searchEnabled;
  final SelectFromTraces.Factory spans;
  final SelectDependencies.Factory dependencies;

  // Everything below here is null when search is disabled
  @Nullable final Call<List<String>> serviceNames;
  @Nullable final SelectRemoteServiceNames.Factory remoteServiceNames;
  @Nullable final SelectSpanNames.Factory spanNames;
  @Nullable final SelectTraceIdTimestampFromServiceName selectTraceIdsByServiceName;
  @Nullable final SelectTraceIdTimestampFromServiceNames selectTraceIdsByServiceNames;
  @Nullable final SelectTraceIdTimestampFromServiceRemoteServiceName
    selectTraceIdsByRemoteServiceName;
  @Nullable final SelectTraceIdTimestampFromServiceSpanName selectTraceIdsBySpanName;
  @Nullable final SelectTraceIdTimestampFromAnnotations selectTraceIdsByAnnotation;

  CassandraSpanStore(CassandraStorage storage) {
    CqlSession session = storage.session();
    Schema.Metadata metadata = storage.metadata();
    maxTraceCols = storage.maxTraceCols;
    indexFetchMultiplier = storage.indexFetchMultiplier;
    strictTraceId = storage.strictTraceId;
    searchEnabled = storage.searchEnabled;

    spans = new SelectFromTraces.Factory(session, strictTraceId, maxTraceCols);
    dependencies = new SelectDependencies.Factory(session);

    if (!searchEnabled) {
      serviceNames = null;
      remoteServiceNames = null;
      spanNames = null;
      selectTraceIdsByServiceName = null;
      selectTraceIdsByServiceNames = null;
      selectTraceIdsByRemoteServiceName = null;
      selectTraceIdsBySpanName = null;
      selectTraceIdsByAnnotation = null;
      return;
    }

    if (metadata.hasRemoteService) {
      selectTraceIdsByRemoteServiceName =
        new SelectTraceIdTimestampFromServiceRemoteServiceName(session);
      remoteServiceNames = new SelectRemoteServiceNames.Factory(session);
    } else {
      selectTraceIdsByRemoteServiceName = null;
      remoteServiceNames = null;
    }
    spanNames = new SelectSpanNames.Factory(session);
    serviceNames = new SelectServiceNames.Factory(session).create();

    selectTraceIdsByServiceName = new SelectTraceIdTimestampFromServiceName(session);

    if (metadata.protocolVersion.getCode() < ProtocolVersion.V4.getCode()) {
      LOG.warn("Please update Cassandra to 2.2 or later, as some features may fail");
      // Log vs failing on "Partition KEY part service_name cannot be restricted by IN relation"
      selectTraceIdsByServiceNames = null;
    } else {
      selectTraceIdsByServiceNames = new SelectTraceIdTimestampFromServiceNames(session);
    }

    selectTraceIdsBySpanName = new SelectTraceIdTimestampFromServiceSpanName(session);

    selectTraceIdsByAnnotation = new SelectTraceIdTimestampFromAnnotations(session);
  }

  @Override public Call<List<List<Span>>> getTraces(QueryRequest request) {
    if (!searchEnabled) return Call.emptyList();

    if (request.minDuration() != null) {
      throw new IllegalArgumentException(
        "getTraces with duration is unsupported. Upgrade to cassandra3.");
    }

    // Over fetch on indexes as they don't return distinct (trace id, timestamp) rows.
    final int traceIndexFetchSize = request.limit() * indexFetchMultiplier;

    List<Call<Set<Pair>>> callsToIntersect = new ArrayList<>();
    List<String> annotationKeys = CassandraUtil.annotationKeys(request);
    if (request.serviceName() != null) {
      String remoteService = request.remoteServiceName();
      if (request.spanName() != null || remoteService != null) {
        if (request.spanName() != null) {
          callsToIntersect.add(
            selectTraceIdsBySpanName.newCall(Input.create(
              request.serviceName() + "." + request.spanName(),
              request.endTs() * 1000,
              request.lookback() * 1000,
              traceIndexFetchSize)));
        }
        if (remoteService != null) {
          if (selectTraceIdsByRemoteServiceName == null) {
            throw new IllegalArgumentException("remoteService=" + remoteService
              + " unsupported due to missing table " + SERVICE_REMOTE_SERVICE_NAME_INDEX);
          }
          callsToIntersect.add(
            selectTraceIdsByRemoteServiceName.newCall(Input.create(
              request.serviceName() + "." + remoteService,
              request.endTs() * 1000,
              request.lookback() * 1000,
              traceIndexFetchSize)));
        }
      } else {
        callsToIntersect.add(
          selectTraceIdsByServiceName.newCall(Input.create(
            request.serviceName(),
            request.endTs() * 1000,
            request.lookback() * 1000,
            traceIndexFetchSize)));
      }
      for (String annotationKey : annotationKeys) {
        callsToIntersect.add(
          selectTraceIdsByAnnotation.newCall(Input.create(
            annotationKey,
            request.endTs() * 1000,
            request.lookback() * 1000,
            traceIndexFetchSize)));
      }
    } else if (selectTraceIdsByServiceNames == null) {
      throw new IllegalArgumentException(
        "getTraces without serviceName requires Cassandra 2.2 or later");
    } else {
      if (!annotationKeys.isEmpty()
        || request.remoteServiceName() != null
        || request.spanName() != null) {
        throw new IllegalArgumentException(
          "getTraces without serviceName supports no other qualifiers. Upgrade to cassandra3.");
      }
      FlatMapper<List<String>, Set<Pair>> flatMapper =
        selectTraceIdsByServiceNames.newFlatMapper(
          request.endTs() * 1000, request.lookback() * 1000, traceIndexFetchSize);
      callsToIntersect.add(getServiceNames().flatMap(flatMapper));
    }

    Call<Set<Long>> traceIdCall;
    if (callsToIntersect.size() == 1) {
      traceIdCall = callsToIntersect.get(0).map(sortTraceIdsByDescTimestampMapper());
    } else {
      // We achieve the AND goal, by intersecting each of the key sets.
      traceIdCall = new IntersectTraceIds(callsToIntersect);
    }

    return traceIdCall.flatMap(spans.newFlatMapper(request));
  }

  @Override public Call<List<Span>> getTrace(String traceId) {
    // make sure we have a 16 or 32 character trace ID
    String normalizedTraceId = Span.normalizeTraceId(traceId);
    return spans.newCall(normalizedTraceId);
  }

  @Override public Call<List<List<Span>>> getTraces(Iterable<String> traceIds) {
    return spans.newCall(traceIds);
  }

  @Override public Call<List<String>> getServiceNames() {
    if (!searchEnabled) return Call.emptyList();
    return serviceNames.clone();
  }

  @Override public Call<List<String>> getRemoteServiceNames(String serviceName) {
    if (serviceName.isEmpty() || !searchEnabled || remoteServiceNames == null) {
      return Call.emptyList();
    }
    return remoteServiceNames.create(serviceName);
  }

  @Override public Call<List<String>> getSpanNames(String serviceName) {
    if (serviceName.isEmpty() || !searchEnabled) return Call.emptyList();
    return spanNames.create(serviceName);
  }

  @Override public Call<List<DependencyLink>> getDependencies(long endTs, long lookback) {
    if (endTs <= 0) throw new IllegalArgumentException("endTs <= 0");
    if (lookback <= 0) throw new IllegalArgumentException("lookback <= 0");
    return dependencies.create(endTs, lookback);
  }

  /** The {@link Pair#left left value} of the pair is the trace ID */
  static final class IntersectTraceIds extends AggregateCall<Set<Pair>, Set<Long>> {
    IntersectTraceIds(List<Call<Set<Pair>>> calls) {
      super(calls);
    }

    @Override protected Set<Long> newOutput() {
      return new LinkedHashSet<>();
    }

    boolean firstInput = true;
    List<Long> inputTraceIds = new ArrayList<>();

    @Override protected void append(Set<Pair> input, Set<Long> output) {
      if (firstInput) {
        firstInput = false;
        output.addAll(sortTraceIdsByDescTimestamp(input));
      } else {
        inputTraceIds.clear();
        for (Pair pair : input) {
          inputTraceIds.add(pair.left);
        }
        output.retainAll(inputTraceIds);
      }
    }

    @Override protected boolean isEmpty(Set<Long> output) {
      return output.isEmpty();
    }

    @Override public IntersectTraceIds clone() {
      return new IntersectTraceIds(cloneCalls());
    }
  }
}
