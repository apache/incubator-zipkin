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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import zipkin2.Span;

import static org.assertj.core.api.Assertions.assertThat;
import static zipkin2.TestObjects.newClientSpan;
import static zipkin2.TestObjects.spanBuilder;

/**
 * Base test for when {@link StorageComponent.Builder#searchEnabled(boolean) searchEnabled ==
 * false}.
 *
 * <p>Subtypes should create a connection to a real backend, even if that backend is in-process.
 */
public abstract class ITSearchEnabledFalse<T extends StorageComponent> extends ITStorage<T> {

  @Override protected final void configureStorageForTest(StorageComponent.Builder storage) {
    storage.searchEnabled(false);
  }

  @Test protected void getTraces_indexDataReturnsNothing(TestInfo testInfo) throws Exception {
    String testSuffix = testSuffix(testInfo);
    Span clientSpan = newClientSpan(testSuffix);
    accept(clientSpan);

    assertGetTracesReturnsEmpty(
      requestBuilder().build());

    assertGetTracesReturnsEmpty(
      requestBuilder().serviceName(clientSpan.localServiceName()).build());

    assertGetTracesReturnsEmpty(
      requestBuilder().spanName(clientSpan.name()).build());

    assertGetTracesReturnsEmpty(
      requestBuilder().annotationQuery(clientSpan.tags()).build());

    assertGetTracesReturnsEmpty(
      requestBuilder().minDuration(clientSpan.duration()).build());
  }

  @Test protected void getServiceNames_isEmpty(TestInfo testInfo) throws Exception {
    String testSuffix = testSuffix(testInfo);
    accept(spanBuilder(testSuffix).build());

    assertThat(names().getServiceNames().execute()).isEmpty();
  }

  @Test protected void getRemoteServiceNames_isEmpty(TestInfo testInfo) throws Exception {
    String testSuffix = testSuffix(testInfo);
    Span span = spanBuilder(testSuffix).build();

    accept(span);

    assertThat(names().getRemoteServiceNames(span.localServiceName()).execute()).isEmpty();
  }

  @Test protected void getSpanNames_isEmpty(TestInfo testInfo) throws Exception {
    String testSuffix = testSuffix(testInfo);
    Span span = spanBuilder(testSuffix).build();

    accept(span);

    assertThat(names().getSpanNames(span.localServiceName()).execute()).isEmpty();
  }
}
