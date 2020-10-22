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

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import zipkin2.Span;
import zipkin2.TestObjects;

import static java.util.Arrays.asList;
import static zipkin2.TestObjects.appendSuffix;
import static zipkin2.TestObjects.newTrace;
import static zipkin2.TestObjects.spanBuilder;

/**
 * Base test for when {@link StorageComponent.Builder#strictTraceId(boolean) strictTraceId ==
 * false}.
 *
 * <p>Subtypes should create a connection to a real backend, even if that backend is in-process.
 *
 * <p>This is a replacement for {@code zipkin.storage.StrictTraceIdFalseTest}.
 */
public abstract class ITStrictTraceIdFalse<T extends StorageComponent> extends ITStorage<T> {

  @Override protected final void configureStorageForTest(StorageComponent.Builder storage) {
    storage.strictTraceId(false);
  }

  /** Ensures we can still lookup fully 128-bit traces when strict trace ID id disabled */
  @Test protected void getTraces_128BitTraceId(TestInfo testInfo) throws Exception {
    getTraces_128BitTraceId(accept128BitTrace(storage, testInfo), testInfo);
  }

  @Test protected void getTraces_128BitTraceId_mixed(TestInfo testInfo) throws Exception {
    getTraces_128BitTraceId(acceptMixedTrace(testInfo), testInfo);
  }

  protected void getTraces_128BitTraceId(List<Span> trace, TestInfo testInfo) throws Exception {
    String testSuffix = testSuffix(testInfo);
    assertGetTracesReturns(requestBuilder().build(), trace);

    String frontend = appendSuffix(TestObjects.FRONTEND.serviceName(), testSuffix);
    String backend = appendSuffix(TestObjects.BACKEND.serviceName(), testSuffix);

    // search by 128-bit side's service and data
    assertGetTracesReturns(
      requestBuilder().serviceName(frontend).parseAnnotationQuery("foo").build(),
      trace);

    // search by 64-bit side's service and data
    assertGetTracesReturns(
      requestBuilder().serviceName(backend).parseAnnotationQuery("error").build(),
      trace);
  }

  @Test protected void getTrace_retrievesBy64Or128BitTraceId(TestInfo testInfo) throws Exception {
    List<Span> trace = accept128BitTrace(storage, testInfo);

    retrievesBy64Or128BitTraceId(trace);
  }

  @Test
  protected void getTrace_retrievesBy64Or128BitTraceId_mixed(TestInfo testInfo) throws Exception {
    List<Span> trace = acceptMixedTrace(testInfo);

    retrievesBy64Or128BitTraceId(trace);
  }

  void retrievesBy64Or128BitTraceId(List<Span> trace) throws IOException {
    String traceId =
      trace.stream().filter(t -> t.traceId().length() == 32).findAny().get().traceId();

    assertGetTraceReturns(traceId, trace);
    assertGetTraceReturns(traceId.substring(16), trace);
  }

  protected List<Span> accept128BitTrace(StorageComponent storage, TestInfo testInfo)
    throws Exception {
    String testSuffix = testSuffix(testInfo);
    List<Span> trace = newTrace(testSuffix);
    Collections.reverse(trace);
    storage.spanConsumer().accept(trace).execute();
    blockWhileInFlight();
    return trace;
  }

  List<Span> acceptMixedTrace(TestInfo testInfo) throws Exception {
    String testSuffix = testSuffix(testInfo);
    List<Span> trace = newTrace(testSuffix);
    String downgraded = trace.get(0).traceId().substring(16);
    // iterate after the outbound client span, emulating a server that downgraded
    for (int i = 2; i < trace.size(); i++) {
      trace.set(i, trace.get(i).toBuilder().traceId(downgraded).build());
    }
    Collections.reverse(trace);
    accept(trace.toArray(new Span[0]));
    return sortTrace(trace);
  }

  /** current implementation cannot return exact form reported */
  @Test protected void getTraces_retrievesBy64Or128BitTraceId(TestInfo testInfo) throws Exception {
    String testSuffix = testSuffix(testInfo);
    Span with128BitId1 = spanBuilder(testSuffix).build();
    Span with64BitId1 = with128BitId1.toBuilder()
      .traceId(with128BitId1.traceId().substring(16)).id("a")
      .timestamp(with128BitId1.timestampAsLong() + 1000L)
      .build();

    Span with128BitId2 = spanBuilder(testSuffix).build();
    Span with64BitId2 = with128BitId2.toBuilder()
      .traceId(with128BitId2.traceId().substring(16)).id("b")
      .timestamp(with128BitId2.timestampAsLong() + 1000L)
      .build();

    Span with128BitId3 = spanBuilder(testSuffix).build();
    Span with64BitId3 = with128BitId3.toBuilder()
      .traceId(with128BitId3.traceId().substring(16)).id("c")
      .timestamp(with128BitId3.timestampAsLong() + 1000L)
      .build();

    accept(with128BitId1, with64BitId1, with128BitId2, with64BitId2, with128BitId3, with64BitId3);

    List<Span>[] trace1And3 =
      new List[] {asList(with128BitId1, with64BitId1), asList(with128BitId3, with64BitId3)};

    assertGetTracesReturns(
      asList(with128BitId1.traceId(), with64BitId1.traceId(), with128BitId3.traceId(),
        with64BitId3.traceId()), trace1And3);

    assertGetTracesReturns(
      asList(with64BitId1.traceId(), with64BitId3.traceId()), trace1And3);

    assertGetTracesReturns(
      asList(with128BitId1.traceId(), with128BitId3.traceId()), trace1And3);
  }
}
