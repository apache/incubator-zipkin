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
package zipkin2.internal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import zipkin2.Call;
import zipkin2.Span;
import zipkin2.storage.SpanStore;
import zipkin2.storage.Traces;

public final class TracesAdapter implements Traces {
  final SpanStore delegate;

  public TracesAdapter(SpanStore spanStore) {
    this.delegate = spanStore;
  }

  @Override public Call<List<Span>> getTrace(String traceId) {
    return delegate.getTrace(traceId);
  }

  @Override public Call<List<List<Span>>> getTraces(Iterable<String> traceIds) {
    if (traceIds == null) throw new NullPointerException("traceIds == null");

    List<Call<List<Span>>> calls = new ArrayList<Call<List<Span>>>();
    for (String traceId : traceIds) {
      calls.add(getTrace(Span.normalizeTraceId(traceId)));
    }

    if (calls.isEmpty()) return Call.emptyList();
    if (calls.size() == 1) return calls.get(0).map(ToListOfTraces.INSTANCE);
    return new ScatterGather(calls);
  }

  enum ToListOfTraces implements Call.Mapper<List<Span>, List<List<Span>>> {
    INSTANCE;

    @Override public List<List<Span>> map(List<Span> input) {
      return input.isEmpty() ? Collections.<List<Span>>emptyList()
        : Collections.singletonList(input);
    }

    @Override public String toString() {
      return "ToListOfTraces()";
    }
  }

  static final class ScatterGather extends AggregateCall<List<Span>, List<List<Span>>> {
    ScatterGather(List<Call<List<Span>>> calls) {
      super(calls);
    }

    @Override protected List<List<Span>> newOutput() {
      return new ArrayList<List<Span>>();
    }

    @Override protected void append(List<Span> input, List<List<Span>> output) {
      if (!input.isEmpty()) output.add(input);
    }

    @Override protected boolean isEmpty(List<List<Span>> output) {
      return output.isEmpty();
    }

    @Override public ScatterGather clone() {
      return new ScatterGather(cloneCalls());
    }
  }

  @Override public String toString() {
    return "TracesAdapter{" + delegate + "}";
  }
}
