/*
 * Copyright 2015-2018 The OpenZipkin Authors
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
package zipkin2.storage.mysql.v1;

import java.util.Collections;
import java.util.List;
import zipkin2.Call;
import zipkin2.DependencyLink;
import zipkin2.Span;
import zipkin2.storage.*;

import static zipkin2.internal.DateUtil.getDays;
import static zipkin2.internal.HexCodec.lowerHexToUnsignedLong;

final class MySQLSpanStore implements SpanStore {

  final DataSourceCall.Factory dataSourceCallFactory;
  final Schema schema;
  final boolean strictTraceId;
  final SelectSpansAndAnnotations.Factory selectFromSpansAndAnnotationsFactory;
  final Call.Mapper<List<Span>, List<List<Span>>> groupByTraceId;
  final DataSourceCall<List<String>> getServiceNamesCall;

  MySQLSpanStore(
      DataSourceCall.Factory dataSourceCallFactory, Schema schema, boolean strictTraceId) {
    this.dataSourceCallFactory = dataSourceCallFactory;
    this.schema = schema;
    this.strictTraceId = strictTraceId;
    this.selectFromSpansAndAnnotationsFactory =
        new SelectSpansAndAnnotations.Factory(schema, strictTraceId);
    this.groupByTraceId = GroupByTraceId.create(strictTraceId);
    this.getServiceNamesCall = dataSourceCallFactory.create(new SelectAnnotationServiceNames());
  }

  @Override
  public Call<List<List<Span>>> getTraces(QueryRequest request) {
    Call<List<List<Span>>> result =
        dataSourceCallFactory
            .create(selectFromSpansAndAnnotationsFactory.create(request))
            .map(groupByTraceId);

    return strictTraceId ? result.map(StrictTraceId.filterTraces(request)) : result;
  }

  @Override
  public Call<List<Span>> getTrace(String hexTraceId) {
    // make sure we have a 16 or 32 character trace ID
    hexTraceId = Span.normalizeTraceId(hexTraceId);
    long traceIdHigh = hexTraceId.length() == 32 ? lowerHexToUnsignedLong(hexTraceId, 0) : 0L;
    long traceId = lowerHexToUnsignedLong(hexTraceId);

    DataSourceCall<List<Span>> result =
        dataSourceCallFactory.create(
            selectFromSpansAndAnnotationsFactory.create(traceIdHigh, traceId));
    return strictTraceId ? result.map(StrictTraceId.filterSpans(hexTraceId)) : result;
  }

  @Override
  public Call<List<List<Span>>> getTraces(List<String> traceIds) {
    Call<List<List<Span>>> result =
      dataSourceCallFactory
        .create(selectFromSpansAndAnnotationsFactory.create(traceIds))
        .map(groupByTraceId);

    return strictTraceId ? result.map(StrictTraceId.filterTraces(traceIds)) : result;
  }

  @Override
  public Call<List<List<Span>>> getTraces(DependencyQueryRequest request) {
    // TODO: refactor to share this code between different SpanStore implementations
    return getDependencies(request.endTs, request.limit).flatMap((links) -> {
      for (DependencyLink link : links) {
        if (request.parentServiceName.equals(link.parent()) && request.childServiceName.equals(link.child())) {
          if (request.errorsOnly) {
            return getTraces(link.errorTraceIds());
          } else {
            return getTraces(link.callTraceIds());
          }
        }
      }

      return Call.create(Collections.emptyList());
    });
  }

  @Override
  public Call<List<String>> getServiceNames() {
    return getServiceNamesCall.clone();
  }

  @Override
  public Call<List<String>> getSpanNames(String serviceName) {
    if (serviceName == null) return Call.emptyList();

    return dataSourceCallFactory.create(new SelectSpanNames(schema, serviceName));
  }

  @Override
  public Call<List<DependencyLink>> getDependencies(long endTs, long lookback) {
    if (schema.hasPreAggregatedDependencies) {
      return dataSourceCallFactory.create(new SelectDependencies(schema, getDays(endTs, lookback)));
    }
    return dataSourceCallFactory.create(
        new AggregateDependencies(schema, endTs * 1000 - lookback * 1000, endTs * 1000));
  }
}
