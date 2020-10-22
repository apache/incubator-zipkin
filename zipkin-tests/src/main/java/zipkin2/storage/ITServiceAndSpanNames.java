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
import java.util.Locale;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import zipkin2.Endpoint;
import zipkin2.Span;

import static org.assertj.core.api.Assertions.assertThat;
import static zipkin2.TestObjects.BACKEND;
import static zipkin2.TestObjects.newClientSpan;
import static zipkin2.TestObjects.spanBuilder;

/**
 * Base test for {@link ServiceAndSpanNames}.
 *
 * <p>Subtypes should create a connection to a real backend, even if that backend is in-process.
 */
public abstract class ITServiceAndSpanNames<T extends StorageComponent> extends ITStorage<T> {

  @Override protected final void configureStorageForTest(StorageComponent.Builder storage) {
    // Defaults are fine.
  }

  @Test
  protected void getLocalServiceNames_includesLocalServiceName(TestInfo testInfo) throws Exception {
    String testSuffix = testSuffix(testInfo);
    Span clientSpan = newClientSpan(testSuffix);

    assertThat(names().getServiceNames().execute())
      .isEmpty();

    accept(clientSpan);

    assertThat(names().getServiceNames().execute())
      .containsOnly(clientSpan.localServiceName());
  }

  @Test protected void getLocalServiceNames_noServiceName(TestInfo testInfo) throws Exception {
    String testSuffix = testSuffix(testInfo);
    accept(spanBuilder(testSuffix).localEndpoint(null).build());

    assertThat(names().getServiceNames().execute()).isEmpty();
  }

  @Test protected void getRemoteServiceNames(TestInfo testInfo) throws Exception {
    String testSuffix = testSuffix(testInfo);
    Span clientSpan = newClientSpan(testSuffix);

    assertThat(names().getRemoteServiceNames(clientSpan.localServiceName()).execute())
      .isEmpty();

    accept(clientSpan);

    assertThat(names().getRemoteServiceNames(clientSpan.localServiceName() + 1).execute())
      .isEmpty();

    assertThat(names().getRemoteServiceNames(clientSpan.localServiceName()).execute())
      .contains(clientSpan.remoteServiceName());
  }

  @Test protected void getRemoteServiceNames_allReturned(TestInfo testInfo) throws Exception {
    String testSuffix = testSuffix(testInfo);
    // Assure a default store limit isn't hit by assuming if 50 are returned, all are returned
    List<Span> spans = IntStream.rangeClosed(0, 50)
      .mapToObj(i -> {
        String suffix = i < 10 ? "0" + i : String.valueOf(i);
        return spanBuilder(testSuffix)
          .id(i + 1)
          .remoteEndpoint(Endpoint.newBuilder().serviceName("yak" + suffix + testSuffix).build())
          .build();
      })
      .collect(Collectors.toList());
    accept(spans);

    assertThat(names().getRemoteServiceNames(spans.get(0).localServiceName()).execute())
      .containsExactlyInAnyOrderElementsOf(spans.stream().map(Span::remoteServiceName)::iterator);
  }

  /** Ensures the service name index returns distinct results */
  @Test protected void getRemoteServiceNames_dedupes(TestInfo testInfo) throws Exception {
    String testSuffix = testSuffix(testInfo);
    List<Span> spans = IntStream.rangeClosed(0, 50)
      .mapToObj(i -> spanBuilder(testSuffix).remoteEndpoint(BACKEND).build())
      .collect(Collectors.toList());
    accept(spans);

    assertThat(names().getRemoteServiceNames(spans.get(0).localServiceName()).execute())
      .containsExactly(BACKEND.serviceName());
  }

  @Test
  protected void getRemoteServiceNames_noRemoteServiceName(TestInfo testInfo) throws Exception {
    String testSuffix = testSuffix(testInfo);
    Span span = spanBuilder(testSuffix).build();
    accept(span);

    assertThat(names().getRemoteServiceNames(span.localServiceName()).execute()).isEmpty();
  }

  @Test
  protected void getRemoteServiceNames_serviceNameGoesLowercase(TestInfo testInfo)
    throws Exception {
    String testSuffix = testSuffix(testInfo);
    Span clientSpan = newClientSpan(testSuffix);

    accept(clientSpan);

    String uppercase = clientSpan.localServiceName().toUpperCase(Locale.ROOT);
    assertThat(names().getRemoteServiceNames(uppercase).execute())
      .containsExactly(clientSpan.remoteServiceName());
  }

  @Test
  protected void getSpanNames_doesNotMapNameToRemoteServiceName(TestInfo testInfo)
    throws Exception {
    String testSuffix = testSuffix(testInfo);
    Span clientSpan = newClientSpan(testSuffix);

    accept(clientSpan);

    assertThat(names().getSpanNames(clientSpan.remoteServiceName()).execute())
      .isEmpty();
  }

  @Test protected void getSpanNames(TestInfo testInfo) throws Exception {
    String testSuffix = testSuffix(testInfo);
    Span span = spanBuilder(testSuffix).build();

    assertThat(names().getSpanNames(span.localServiceName()).execute())
      .isEmpty();

    accept(span);

    assertThat(names().getSpanNames(span.localServiceName() + 1).execute())
      .isEmpty();

    assertThat(names().getSpanNames(span.localServiceName()).execute())
      .contains(span.name());
  }

  @Test protected void getSpanNames_allReturned(TestInfo testInfo) throws Exception {
    String testSuffix = testSuffix(testInfo);
    // Assure a default store limit isn't hit by assuming if 50 are returned, all are returned
    List<Span> spans = IntStream.rangeClosed(0, 50)
      .mapToObj(i -> {
        String suffix = i < 10 ? "0" + i : String.valueOf(i);
        return spanBuilder(testSuffix).name("yak" + suffix).build();
      })
      .collect(Collectors.toList());
    accept(spans);

    assertThat(names().getSpanNames(spans.get(0).localServiceName()).execute())
      .containsExactlyInAnyOrderElementsOf(spans.stream().map(Span::name)::iterator);
  }

  /** Ensures the span name index returns distinct results */
  @Test protected void getSpanNames_dedupes(TestInfo testInfo) throws Exception {
    String testSuffix = testSuffix(testInfo);
    List<Span> spans = IntStream.rangeClosed(0, 50)
      .mapToObj(i -> spanBuilder(testSuffix).build())
      .collect(Collectors.toList());
    accept(spans);

    assertThat(names().getSpanNames(spans.get(0).localServiceName()).execute())
      .containsExactly(spans.get(0).name());
  }

  @Test protected void getSpanNames_noSpanName(TestInfo testInfo) throws Exception {
    String testSuffix = testSuffix(testInfo);
    Span span = spanBuilder(testSuffix).name(null).build();
    accept(span);

    assertThat(names().getSpanNames(span.localServiceName()).execute()).isEmpty();
  }

  @Test protected void getSpanNames_serviceNameGoesLowercase(TestInfo testInfo) throws Exception {
    String testSuffix = testSuffix(testInfo);
    Span span = spanBuilder(testSuffix).build();
    accept(span);

    String uppercase = span.localServiceName().toUpperCase(Locale.ROOT);
    assertThat(names().getSpanNames(uppercase).execute())
      .containsExactly(span.name());
  }
}
