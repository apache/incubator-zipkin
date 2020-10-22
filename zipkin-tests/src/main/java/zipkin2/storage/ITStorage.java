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
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.TestInstance;
import org.opentest4j.TestAbortedException;
import zipkin2.CheckResult;
import zipkin2.Span;
import zipkin2.internal.Trace;

import static java.util.Arrays.asList;
import static org.assertj.core.api.Assertions.assertThat;
import static zipkin2.TestObjects.DAY;
import static zipkin2.TestObjects.TODAY;

/** Base class for all {@link StorageComponent} integration tests. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class ITStorage<T extends StorageComponent> {
  protected T storage;

  @BeforeAll void initializeStorage(TestInfo testInfo) {
    if (initializeStoragePerTest()) return;
    doInitializeStorage(testInfo);
  }

  @BeforeEach void initializeStorageForTest(TestInfo testInfo) {
    if (!initializeStoragePerTest()) return;
    doInitializeStorage(testInfo);
  }

  void doInitializeStorage(TestInfo testInfo) {
    StorageComponent.Builder builder = newStorageBuilder(testInfo);
    configureStorageForTest(builder);
    // TODO(anuraaga): It wouldn't be difficult to allow storage builders to be parameterized by
    // their storage type.
    @SuppressWarnings("unchecked")
    T storage = (T) builder.build();
    this.storage = storage;
    checkStorage();
  }

  protected void checkStorage() {
    CheckResult check = storage.check();
    if (!check.ok()) {
      throw new TestAbortedException("Could not connect to storage, skipping test: "
        + check.error().getMessage(), check.error());
    }
  }

  @AfterAll void closeStorage() throws Exception {
    if (initializeStoragePerTest()) return;
    storage.close();
  }

  @AfterEach void closeStorageForTest() throws Exception {
    if (!initializeStoragePerTest()) return;
    storage.close();
  }

  @AfterEach void clearStorage() throws Exception {
    clear();
  }

  /**
   * Sets the test to initialise the {@link StorageComponent} before each test rather than the test
   * class. Generally, tests will run faster if the storage is initialized as infrequently as
   * possibly while clearing data between runs, but for certain backends like Cassandra, it's
   * difficult to reliably clear data between runs and tends to be very slow anyways.
   */
  protected boolean initializeStoragePerTest() {
    return false;
  }

  /**
   * Returns a new {@link StorageComponent.Builder} for connecting to the backend for the test.
   */
  protected abstract StorageComponent.Builder newStorageBuilder(TestInfo testInfo);

  /**
   * Configures a {@link StorageComponent.Builder} with parameters for the test being executed.
   */
  protected abstract void configureStorageForTest(StorageComponent.Builder storage);

  protected Traces traces() {
    return storage.traces();
  }

  protected SpanStore store() {
    return storage.spanStore();
  }

  protected ServiceAndSpanNames names() {
    return storage.serviceAndSpanNames();
  }

  protected final void accept(Span... spans) throws IOException {
    accept(asList(spans));
  }

  protected final void accept(List<Span> spans) throws IOException {
    for (int i = 0, length = spans.size(); i < length; i += 100) {
      storage.spanConsumer().accept(spans.subList(i, Math.min(length, i + 100))).execute();
      blockWhileInFlight();
    }
  }

  // Blocks between writes of 100 spans to help avoid readback problems.
  protected void blockWhileInFlight() {
  }

  /** Clears store between tests. */
  protected abstract void clear() throws Exception;

  protected static QueryRequest.Builder requestBuilder() {
    return QueryRequest.newBuilder().endTs(TODAY + DAY).lookback(DAY * 2).limit(100);
  }

  protected void assertGetTracesReturns(QueryRequest request, List<Span>... traces)
    throws IOException {
    assertThat(sortTraces(store().getTraces(request).execute()))
      .containsAll(sortTraces(asList(traces)));
  }

  protected void assertGetTraceReturns(Span onlySpan) throws IOException {
    assertGetTraceReturns(onlySpan.traceId(), asList(onlySpan));
  }

  protected void assertGetTraceReturns(String traceId, List<Span> trace) throws IOException {
    assertThat(sortTrace(storage.traces().getTrace(traceId).execute()))
      .containsAll(sortTrace(trace));
  }

  protected void assertGetTraceReturnsEmpty(String traceId)
    throws IOException {
    List<Span> results = sortTrace(storage.traces().getTrace(traceId).execute());
    assertThat(results)
      .withFailMessage("Expected no traces for traceId <%s>, but received <%s>", traceId, results)
      .isEmpty();
  }

  protected void assertGetTracesReturns(List<String> traceIds, List<Span>... traces)
    throws IOException {
    assertThat(sortTraces(storage.traces().getTraces(traceIds).execute()))
      .containsAll(sortTraces(asList(traces)));
  }

  protected void assertGetTracesReturnsEmpty(List<String> traceIds) throws IOException {
    List<List<Span>> results = sortTraces(storage.traces().getTraces(traceIds).execute());
    assertThat(results)
      .withFailMessage("Expected no traces for traceIds <%s>, but received <%s>", traceIds, results)
      .isEmpty();
  }

  protected void assertGetTracesReturnsCount(QueryRequest request, int traceCount)
    throws IOException {
    int countReturned = store().getTraces(request).execute().size();
    assertThat(countReturned)
      .withFailMessage("Expected <%s> traces for request <%s>, but received <%s>",
        traceCount, request, countReturned)
      .isEqualTo(traceCount);
  }

  protected void assertGetTracesReturnsEmpty(QueryRequest request) throws IOException {
    List<List<Span>> results = sortTraces(store().getTraces(request).execute());
    assertThat(results)
      .withFailMessage("Expected no traces for request <%s>, but received <%s>", request, results)
      .isEmpty();
  }

  List<List<Span>> sortTraces(List<List<Span>> traces) {
    List<List<Span>> result = new ArrayList<>();
    for (List<Span> trace : traces) {
      result.add(sortTrace(trace));
    }
    return result;
  }

  /** Override for storage that does upserts and cannot return the original spans. */
  protected boolean returnsRawSpans() {
    return true;
  }

  /** Used to help tests from colliding too much */
  protected static String testSuffix(TestInfo testInfo) {
    String result;
    if (testInfo.getTestMethod().isPresent()) {
      result = testInfo.getTestMethod().get().getName();
    } else {
      assert testInfo.getTestClass().isPresent();
      result = testInfo.getTestClass().get().getSimpleName();
    }
    result = result.toLowerCase();
    return result.length() <= 48 ? result : result.substring(result.length() - 48);
  }

  protected List<Span> sortTrace(List<Span> trace) {
    if (!returnsRawSpans()) trace = Trace.merge(trace);
    List<Span> result = new ArrayList<>(trace);
    // Sort so that tests aren't flakey. Spans are not required to be in any order by contract.
    // However, when writing tests, we shouldn't use data that can appear in random order.
    result.sort((l, r) -> {
      int traceId = l.traceId().compareTo(r.traceId());
      if (traceId != 0) return traceId;
      int id = l.id().compareTo(r.id());
      if (id != 0) return id;

      if (l.name() != null && r.name() != null) {
        int name = l.name().compareTo(r.name());
        if (name != 0) return name;
      }

      int timestamp = Long.compare(l.timestampAsLong(), r.timestampAsLong());
      if (timestamp != 0) return timestamp;

      int duration = Long.compare(l.durationAsLong(), r.durationAsLong());
      if (duration != 0) return duration;

      throw new AssertionError("Don't use test data that results in indeterministic ordering:\n" +
        "l=" + l + ", r=" + r);
    });
    return result;
  }
}
