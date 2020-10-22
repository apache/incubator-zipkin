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

import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import zipkin2.Span;

import static java.util.Arrays.asList;
import static zipkin2.TestObjects.newClientSpan;
import static zipkin2.TestObjects.newTraceId;
import static zipkin2.TestObjects.spanBuilder;

/**
 * Base test for {@link Traces}.
 *
 * <p>Subtypes should create a connection to a real backend, even if that backend is in-process.
 */
public abstract class ITTraces<T extends StorageComponent> extends ITStorage<T> {

  @Override protected final void configureStorageForTest(StorageComponent.Builder storage) {
    // Defaults are fine.
  }

  @Test protected void getTrace_returnsEmptyOnNotFound(TestInfo testInfo) throws Exception {
    String testSuffix = testSuffix(testInfo);
    Span clientSpan = newClientSpan(testSuffix);

    assertGetTraceReturnsEmpty(clientSpan.traceId());

    accept(clientSpan);

    assertGetTraceReturns(clientSpan);

    assertGetTraceReturnsEmpty(clientSpan.traceId().substring(16));
  }

  @Test protected void getTraces_onlyReturnsTracesThatMatch(TestInfo testInfo) throws Exception {
    String testSuffix = testSuffix(testInfo);
    Span span1 = spanBuilder(testSuffix).build(), span2 = spanBuilder(testSuffix).build();
    List<String> traceIds = asList(span1.traceId(), newTraceId());

    assertGetTracesReturnsEmpty(traceIds);

    accept(span1, span2);

    assertGetTracesReturns(traceIds, asList(span1));

    List<String> shortTraceIds =
      traceIds.stream().map(t -> t.substring(16)).collect(Collectors.toList());
    assertGetTracesReturnsEmpty(shortTraceIds);
  }

  @Test protected void getTraces_returnsEmptyOnNotFound(TestInfo testInfo) throws Exception {
    String testSuffix = testSuffix(testInfo);
    Span span1 = spanBuilder(testSuffix).build(), span2 = spanBuilder(testSuffix).build();
    List<String> traceIds = asList(span1.traceId(), span2.traceId());

    assertGetTracesReturnsEmpty(traceIds);

    accept(span1, span2);

    assertGetTracesReturns(traceIds, asList(span1), asList(span2));

    List<String> shortTraceIds =
      traceIds.stream().map(t -> t.substring(16)).collect(Collectors.toList());
    assertGetTracesReturnsEmpty(shortTraceIds);
  }

  /**
   * Ideally, storage backends can deduplicate identical documents as this will prevent some
   * analysis problems such as double-counting dependency links or other statistics. While this test
   * exists, it is known not all backends will be able to cheaply make it pass. In other words, it
   * is optional.
   */
  @Test protected void getTrace_deduplicates(TestInfo testInfo) throws Exception {
    String testSuffix = testSuffix(testInfo);
    Span span = spanBuilder(testSuffix).build();

    // simulate a re-processed message
    accept(span);
    accept(span);

    assertGetTraceReturns(span);
  }
}
