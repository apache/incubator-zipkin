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
package zipkin2.storage;

import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import zipkin2.Span;

import static java.util.Arrays.asList;
import static zipkin2.TestObjects.TODAY;
import static zipkin2.TestObjects.spanBuilder;

/**
 * Base heavy tests for {@link SpanStore} implementations. Subtypes should create a connection to a
 * real backend, even if that backend is in-process.
 *
 * <p>As these tests create a lot of data, implementations may wish to isolate them from other
 * integration tests such as {@link ITSpanStore}
 */
public abstract class ITSpanStoreHeavy<T extends StorageComponent> extends ITStorage<T> {
  @Override protected boolean initializeStoragePerTest() {
    return true;
  }

  @Override protected final void configureStorageForTest(StorageComponent.Builder storage) {
    // Defaults are fine.
  }

  // Bugs have happened in the past where trace limit was mistaken for span count.
  @Test protected void traceWithManySpans(TestInfo testInfo) throws Exception {
    String testSuffix = testSuffix(testInfo);
    Span span = spanBuilder(testSuffix).build();

    int traceCount = 101;
    Span[] spans = new Span[traceCount];
    spans[0] = span;

    IntStream.range(1, spans.length).forEach(i ->
      spans[i] = span.toBuilder().parentId(span.id()).id(i)
        .timestamp((TODAY + i) * 1000).duration(10L)
        .build());

    accept(spans);

    assertGetTracesReturns(requestBuilder().build(), asList(spans));
    assertGetTraceReturns(span.traceId(), asList(spans));
  }

  /**
   * Formerly, a bug was present where cassandra didn't index more than bucket count traces per
   * millisecond. This stores a lot of spans to ensure indexes work under high-traffic scenarios.
   */
  @Test protected void getTraces_manyTraces(TestInfo testInfo) throws Exception {
    String testSuffix = testSuffix(testInfo);
    Span span = spanBuilder(testSuffix).build();
    Map.Entry<String, String> tag = span.tags().entrySet().iterator().next();

    int traceCount = 1000;
    Span[] traces = new Span[traceCount];
    traces[0] = span;

    IntStream.range(1, traces.length).forEach(i ->
      traces[i] = spanBuilder(testSuffix)
        .timestamp((TODAY + i) * 1000).duration(10L)
        .build());

    accept(traces);

    assertGetTracesReturnsCount(requestBuilder().limit(traceCount).build(), traceCount);

    QueryRequest.Builder builder =
      requestBuilder().limit(traceCount).serviceName(span.localServiceName());

    assertGetTracesReturnsCount(
      builder.build(), traceCount);

    assertGetTracesReturnsCount(
      builder.remoteServiceName(span.remoteServiceName()).build(), traceCount);

    assertGetTracesReturnsCount(
      builder.spanName(span.name()).build(), traceCount);

    assertGetTracesReturnsCount(
      builder.parseAnnotationQuery(tag.getKey() + "=" + tag.getValue()).build(), traceCount);
  }
}
