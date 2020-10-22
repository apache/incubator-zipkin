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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.TestInstance;
import zipkin2.Annotation;
import zipkin2.DependencyLink;
import zipkin2.Endpoint;
import zipkin2.Span;
import zipkin2.Span.Kind;
import zipkin2.TestObjects;
import zipkin2.internal.DependencyLinker;
import zipkin2.v1.V1Span;
import zipkin2.v1.V1SpanConverter;

import static java.util.Arrays.asList;
import static java.util.stream.Collectors.toList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static zipkin2.TestObjects.DAY;
import static zipkin2.TestObjects.TODAY;
import static zipkin2.TestObjects.appendSuffix;
import static zipkin2.TestObjects.endTs;
import static zipkin2.TestObjects.midnightUTC;
import static zipkin2.TestObjects.newTrace;
import static zipkin2.TestObjects.newTraceId;
import static zipkin2.TestObjects.startTs;
import static zipkin2.TestObjects.suffixServiceName;

/**
 * Base test for {@link SpanStore} implementations that support dependency aggregation. Subtypes
 * should create a connection to a real backend, even if that backend is in-process.
 *
 * <p>This is a replacement for {@code zipkin.storage.DependenciesTest}. There is some redundancy
 * as {@code zipkin2.internal.DependencyLinkerTest} also defines many of these tests. The redundancy
 * helps ensure integrated storage doesn't fail due to mismapping of data, for example.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class ITDependencies<T extends StorageComponent> extends ITStorage<T> {
  @Override protected final void configureStorageForTest(StorageComponent.Builder storage) {
    // Defaults are fine.
  }

  /**
   * Override if dependency processing is a separate job: it should complete before returning from
   * this method.
   */
  protected void processDependencies(List<Span> spans) throws Exception {
    assertThat(spans).isNotEmpty(); // bug if it were!

    storage.spanConsumer().accept(spans).execute();
    blockWhileInFlight();
  }

  /**
   * Normally, the root-span is where trace id == span id and parent id == null. The default is to
   * look back one day from today.
   */
  @Test protected void getDependencies(TestInfo testInfo) throws Exception {
    String testSuffix = testSuffix(testInfo);
    List<Span> trace = newTrace(testSuffix);

    processDependencies(trace);

    assertThat(store().getDependencies(endTs(trace), DAY).execute())
      .containsExactlyInAnyOrderElementsOf(links(testSuffix));
  }

  /**
   * This tests that dependency linking ignores the high-bits of the trace ID when grouping spans
   * for dependency links. This allows environments with 64-bit instrumentation to participate in
   * the same trace as 128-bit instrumentation.
   */
  @Test protected void getDependencies_linksMixedTraceId(TestInfo testInfo) throws Exception {
    String testSuffix = testSuffix(testInfo);
    String traceId = newTraceId();

    Endpoint frontend = suffixServiceName(TestObjects.FRONTEND, testSuffix);
    Endpoint backend = suffixServiceName(TestObjects.BACKEND, testSuffix);

    List<Span> mixedTrace = asList(
      Span.newBuilder().traceId(traceId).id("1").name("get")
        .kind(Kind.SERVER)
        .timestamp(TODAY * 1000L)
        .duration(350 * 1000L)
        .localEndpoint(frontend)
        .build(),
      // the server dropped traceIdHigh
      Span.newBuilder().traceId(traceId.substring(16)).parentId("1").id("2").name("get")
        .kind(Kind.SERVER).shared(true)
        .timestamp((TODAY + 100) * 1000L)
        .duration(250 * 1000L)
        .localEndpoint(backend)
        .build(),
      Span.newBuilder().traceId(traceId).parentId("1").id("2")
        .kind(Kind.CLIENT)
        .timestamp((TODAY + 50) * 1000L)
        .duration(300 * 1000L)
        .localEndpoint(frontend)
        .build()
    );

    processDependencies(mixedTrace);

    assertThat(store().getDependencies(endTs(mixedTrace), DAY).execute())
      .containsOnly(DependencyLink.newBuilder()
        .parent(frontend.serviceName())
        .child(backend.serviceName())
        .callCount(1).build()
      );
  }

  /** It should be safe to run dependency link jobs twice */
  @Test protected void replayOverwrites(TestInfo testInfo) throws Exception {
    String testSuffix = testSuffix(testInfo);
    List<Span> trace = newTrace(testSuffix);

    processDependencies(trace);
    processDependencies(trace);

    assertThat(store().getDependencies(endTs(trace), DAY).execute())
      .containsExactlyInAnyOrderElementsOf(links(testSuffix));
  }

  /** Edge-case when there are no spans, or instrumentation isn't logging annotations properly. */
  @Test protected void empty() throws Exception {
    assertThat(store().getDependencies(TODAY, DAY).execute())
      .isEmpty();
  }

  /**
   * When all servers are instrumented, they all record {@link Kind#SERVER} and the {@link
   * Span#localEndpoint()} indicates the service.
   */
  @Test protected void getDependenciesAllInstrumented(TestInfo testInfo) throws Exception {
    String testSuffix = testSuffix(testInfo);
    String traceId = newTraceId();

    String frontend = appendSuffix(TestObjects.FRONTEND.serviceName(), testSuffix);
    String backend = appendSuffix(TestObjects.BACKEND.serviceName(), testSuffix);
    String db = appendSuffix(TestObjects.DB.serviceName(), testSuffix);

    Endpoint one = Endpoint.newBuilder().serviceName(frontend).ip("127.0.0.1").build();
    Endpoint onePort3001 = one.toBuilder().port(3001).build();
    Endpoint two = Endpoint.newBuilder().serviceName(backend).ip("127.0.0.2").build();
    Endpoint twoPort3002 = two.toBuilder().port(3002).build();
    Endpoint three = Endpoint.newBuilder().serviceName(db).ip("127.0.0.3").build();

    List<Span> trace = asList(
      Span.newBuilder().traceId(traceId).id("10").name("get")
        .kind(Kind.SERVER)
        .timestamp(TODAY * 1000L)
        .duration(350 * 1000L)
        .localEndpoint(one)
        .build(),
      Span.newBuilder().traceId(traceId).parentId("10").id("20").name("get")
        .kind(Kind.CLIENT)
        .timestamp((TODAY + 50) * 1000L)
        .duration(250 * 1000L)
        .localEndpoint(onePort3001)
        .build(),
      Span.newBuilder().traceId(traceId).parentId("10").id("20").name("get").shared(true)
        .kind(Kind.SERVER)
        .timestamp((TODAY + 100) * 1000L)
        .duration(150 * 1000L)
        .localEndpoint(two)
        .build(),
      Span.newBuilder().traceId(traceId).parentId("20").id("30").name("query")
        .kind(Kind.CLIENT)
        .timestamp((TODAY + 150) * 1000L)
        .duration(50 * 1000L)
        .localEndpoint(twoPort3002)
        .build(),
      Span.newBuilder().traceId(traceId).parentId("20").id("30").name("query").shared(true)
        .kind(Kind.SERVER)
        .timestamp((TODAY + 160) * 1000L)
        .duration(20 * 1000L)
        .localEndpoint(three)
        .build()
    );

    processDependencies(trace);

    assertThat(store().getDependencies(endTs(trace), DAY).execute()).containsOnly(
      DependencyLink.newBuilder()
        .parent(frontend)
        .child(backend)
        .callCount(1)
        .build(),
      DependencyLink.newBuilder()
        .parent(backend)
        .child(db)
        .callCount(1)
        .build()
    );
  }

  @Test protected void dependencies_loopback(TestInfo testInfo) throws Exception {
    String testSuffix = testSuffix(testInfo);
    List<Span> trace = newTrace(testSuffix);

    List<Span> traceWithLoopback = asList(
      trace.get(0),
      trace.get(1).toBuilder().remoteEndpoint(trace.get(0).localEndpoint()).build()
    );

    processDependencies(traceWithLoopback);

    String frontend = appendSuffix(TestObjects.FRONTEND.serviceName(), testSuffix);

    assertThat(store().getDependencies(endTs(trace), DAY).execute()).containsOnly(
      DependencyLink.newBuilder().parent(frontend).child(frontend).callCount(1).build()
    );
  }

  /**
   * Some systems log a different trace id than the root span. This seems "headless", as we won't
   * see a span whose id is the same as the trace id.
   */
  @Test protected void dependencies_headlessTrace(TestInfo testInfo) throws Exception {
    String testSuffix = testSuffix(testInfo);
    ArrayList<Span> trace = new ArrayList<>(newTrace(testSuffix));
    trace.remove(0);
    processDependencies(trace);

    assertThat(store().getDependencies(endTs(trace), DAY).execute())
      .containsExactlyInAnyOrderElementsOf(links(testSuffix));
  }

  @Test protected void looksBackIndefinitely(TestInfo testInfo) throws Exception {
    String testSuffix = testSuffix(testInfo);
    List<Span> trace = newTrace(testSuffix);

    processDependencies(trace);

    assertThat(store().getDependencies(endTs(trace), DAY).execute())
      .containsExactlyInAnyOrderElementsOf(links(testSuffix));
  }

  /** Ensure complete traces are aggregated, even if they complete after endTs */
  @Test protected void endTsInsideTheTrace(TestInfo testInfo) throws Exception {
    String testSuffix = testSuffix(testInfo);
    List<Span> trace = newTrace(testSuffix);

    processDependencies(trace);

    assertThat(store().getDependencies(startTs(trace) + 100, 200).execute())
      .containsExactlyInAnyOrderElementsOf(links(testSuffix));
  }

  @Test protected void endTimeBeforeData(TestInfo testInfo) throws Exception {
    String testSuffix = testSuffix(testInfo);
    List<Span> trace = newTrace(testSuffix);

    processDependencies(trace);

    assertThat(store().getDependencies(startTs(trace) - 1000L, 1000L).execute())
      .isEmpty();
  }

  @Test protected void lookbackAfterData(TestInfo testInfo) throws Exception {
    String testSuffix = testSuffix(testInfo);
    List<Span> trace = newTrace(testSuffix);

    processDependencies(trace);

    assertThat(store().getDependencies(TODAY + 2 * DAY, DAY).execute())
      .isEmpty();
  }

  /**
   * This test confirms that the span store can detect dependency indicated by local and remote
   * endpoint. Specifically, this detects an uninstrumented client before the trace and an
   * uninstrumented server at the end of it.
   */
  @Test protected void notInstrumentedClientAndServer(TestInfo testInfo) throws Exception {
    String testSuffix = testSuffix(testInfo);
    String traceId = newTraceId();

    Endpoint kafka = suffixServiceName(TestObjects.KAFKA, testSuffix);
    Endpoint frontend = suffixServiceName(TestObjects.FRONTEND, testSuffix);
    Endpoint backend = suffixServiceName(TestObjects.BACKEND, testSuffix);
    Endpoint db = suffixServiceName(TestObjects.DB, testSuffix);

    List<Span> trace = asList(
      Span.newBuilder().traceId(traceId).id("20").name("get")
        .timestamp(TODAY * 1000L).duration(350L * 1000L)
        .kind(Kind.SERVER)
        .localEndpoint(frontend)
        .remoteEndpoint(kafka)
        .build(),
      Span.newBuilder().traceId(traceId).parentId("20").id("21").name("get")
        .timestamp((TODAY + 50L) * 1000L).duration(250L * 1000L)
        .kind(Kind.CLIENT)
        .localEndpoint(frontend)
        .build(),
      Span.newBuilder().traceId(traceId).parentId("20").id("21").name("get").shared(true)
        .timestamp((TODAY + 250) * 1000L).duration(50L * 1000L)
        .kind(Kind.SERVER)
        .localEndpoint(backend)
        .build(),
      Span.newBuilder().traceId(traceId).parentId("21").id("22").name("get")
        .timestamp((TODAY + 150L) * 1000L).duration(50L * 1000L)
        .kind(Kind.CLIENT)
        .localEndpoint(backend)
        .remoteEndpoint(db)
        .build()
    );

    processDependencies(trace);

    assertThat(store().getDependencies(endTs(trace), DAY).execute()).containsOnly(
      DependencyLink.newBuilder()
        .parent(kafka.serviceName())
        .child(frontend.serviceName())
        .callCount(1)
        .build(),
      DependencyLink.newBuilder()
        .parent(frontend.serviceName())
        .child(backend.serviceName())
        .callCount(1)
        .build(),
      DependencyLink.newBuilder()
        .parent(backend.serviceName())
        .child(db.serviceName())
        .callCount(1)
        .build()
    );
  }

  /** This tests we error prior to executing the call. */
  @Test protected void endTsAndLookbackMustBePositive() {
    SpanStore store = store();
    assertThatThrownBy(() -> store.getDependencies(0L, DAY))
      .isInstanceOf(IllegalArgumentException.class)
      .hasMessage("endTs <= 0");

    assertThatThrownBy(() -> store.getDependencies(TODAY, 0L))
      .isInstanceOf(IllegalArgumentException.class)
      .hasMessage("lookback <= 0");
  }

  @Test protected void instrumentedClientAndServer(TestInfo testInfo) throws Exception {
    String testSuffix = testSuffix(testInfo);
    String traceId = newTraceId();

    Endpoint frontend = suffixServiceName(TestObjects.FRONTEND, testSuffix);
    Endpoint backend = suffixServiceName(TestObjects.BACKEND, testSuffix);
    Endpoint db = suffixServiceName(TestObjects.DB, testSuffix);

    List<Span> trace = asList(
      Span.newBuilder().traceId(traceId).id("10").name("get")
        .timestamp((TODAY + 50L) * 1000L).duration(250L * 1000L)
        .kind(Kind.CLIENT)
        .localEndpoint(frontend)
        .build(),
      Span.newBuilder().traceId(traceId).id("10").name("get").shared(true)
        .timestamp((TODAY + 100) * 1000L).duration(150L * 1000L)
        .kind(Kind.SERVER)
        .localEndpoint(backend)
        .build(),
      Span.newBuilder().traceId(traceId).parentId("10").id("11").name("get")
        .timestamp((TODAY + 150L) * 1000L).duration(50L * 1000L)
        .kind(Kind.CLIENT)
        .localEndpoint(backend)
        .remoteEndpoint(db)
        .build()
    );

    processDependencies(trace);

    assertThat(store().getDependencies(endTs(trace), DAY).execute()).containsOnly(
      DependencyLink.newBuilder()
        .parent(frontend.serviceName())
        .child(backend.serviceName())
        .callCount(1)
        .build(),
      DependencyLink.newBuilder()
        .parent(backend.serviceName())
        .child(db.serviceName())
        .callCount(1)
        .build()
    );
  }

  @Test protected void instrumentedProducerAndConsumer(TestInfo testInfo) throws Exception {
    String testSuffix = testSuffix(testInfo);
    String traceId = newTraceId();

    Endpoint kafka = suffixServiceName(TestObjects.KAFKA, testSuffix);
    Endpoint frontend = suffixServiceName(TestObjects.FRONTEND, testSuffix);
    Endpoint backend = suffixServiceName(TestObjects.BACKEND, testSuffix);

    List<Span> trace = asList(
      Span.newBuilder().traceId(traceId).id("10").name("send")
        .timestamp((TODAY + 50L) * 1000L).duration(1)
        .kind(Kind.PRODUCER)
        .localEndpoint(frontend)
        .remoteEndpoint(kafka)
        .build(),
      Span.newBuilder().traceId(traceId).parentId("10").id("11").name("receive")
        .timestamp((TODAY + 100) * 1000L).duration(1)
        .kind(Kind.CONSUMER)
        .remoteEndpoint(kafka)
        .localEndpoint(backend)
        .build()
    );

    processDependencies(trace);

    assertThat(store().getDependencies(endTs(trace), DAY).execute()).containsOnly(
      DependencyLink.newBuilder()
        .parent(frontend.serviceName())
        .child(kafka.serviceName())
        .callCount(1)
        .build(),
      DependencyLink.newBuilder()
        .parent(kafka.serviceName())
        .child(backend.serviceName())
        .callCount(1)
        .build()
    );
  }

  /** This shows a missing parent still results in a dependency link when local endpoints change */
  @Test protected void missingIntermediateSpan(TestInfo testInfo) throws Exception {
    String testSuffix = testSuffix(testInfo);
    String traceId = newTraceId();

    Endpoint frontend = suffixServiceName(TestObjects.FRONTEND, testSuffix);
    Endpoint backend = suffixServiceName(TestObjects.BACKEND, testSuffix);

    List<Span> trace = asList(
      Span.newBuilder().traceId(traceId).id("20").name("get")
        .timestamp(TODAY * 1000L).duration(350L * 1000L)
        .kind(Kind.SERVER)
        .localEndpoint(frontend)
        .build(),
      // missing an intermediate span
      Span.newBuilder().traceId(traceId).parentId("21").id("22").name("get")
        .timestamp((TODAY + 150L) * 1000L).duration(50L * 1000L)
        .kind(Kind.CLIENT)
        .localEndpoint(backend)
        .build()
    );

    processDependencies(trace);

    assertThat(store().getDependencies(endTs(trace), DAY).execute()).containsOnly(
      DependencyLink.newBuilder()
        .parent(frontend.serviceName())
        .child(backend.serviceName())
        .callCount(1)
        .build()
    );
  }

  /**
   * This test shows that dependency links can be filtered at daily granularity. This allows the UI
   * to look for dependency intervals besides TODAY.
   */
  @Test protected void canSearchForIntervalsBesidesToday(TestInfo testInfo) throws Exception {
    String testSuffix = testSuffix(testInfo);
    List<Span> trace = newTrace(testSuffix);

    // Let's pretend we have two days of data processed
    //  - Note: calling this twice allows test implementations to consider timestamps
    processDependencies(subtractDay(trace));
    processDependencies(trace);

    // A user looks at today's links.
    //  - Note: Using the smallest lookback avoids bumping into implementation around windowing.
    long lookback = trace.get(0).durationAsLong() / 1000L;
    assertThat(store().getDependencies(endTs(trace), lookback).execute())
      .containsExactlyInAnyOrderElementsOf(links(testSuffix));

    // A user compares the links from those a day ago.
    assertThat(store().getDependencies(endTs(trace) - DAY, DAY).execute())
      .containsExactlyInAnyOrderElementsOf(links(testSuffix));

    // A user looks at all links since data started
    String frontend = appendSuffix(TestObjects.FRONTEND.serviceName(), testSuffix);
    String backend = appendSuffix(TestObjects.BACKEND.serviceName(), testSuffix);
    String db = appendSuffix(TestObjects.DB.serviceName(), testSuffix);

    assertThat(store().getDependencies(endTs(trace), DAY * 2).execute()).containsOnly(
      DependencyLink.newBuilder().parent(frontend).child(backend).callCount(2L).build(),
      DependencyLink.newBuilder().parent(backend).child(db).callCount(2L).errorCount(2L).build()
    );
  }

  @Test
  protected void spanKindIsNotRequiredWhenEndpointsArePresent(TestInfo testInfo) throws Exception {
    String testSuffix = testSuffix(testInfo);
    String traceId = newTraceId();

    Endpoint kafka = suffixServiceName(TestObjects.KAFKA, testSuffix);
    Endpoint frontend = suffixServiceName(TestObjects.FRONTEND, testSuffix);
    Endpoint backend = suffixServiceName(TestObjects.BACKEND, testSuffix);
    Endpoint db = suffixServiceName(TestObjects.DB, testSuffix);

    List<Span> trace = asList(
      Span.newBuilder().traceId(traceId).id("20").name("get")
        .timestamp(TODAY * 1000L).duration(350L * 1000L)
        .localEndpoint(kafka)
        .remoteEndpoint(frontend).build(),
      Span.newBuilder().traceId(traceId).parentId("20").id("21").name("get")
        .timestamp((TODAY + 50) * 1000L).duration(250L * 1000L)
        .localEndpoint(frontend)
        .remoteEndpoint(backend).build(),
      Span.newBuilder().traceId(traceId).parentId("21").id("22").name("get")
        .timestamp((TODAY + 150) * 1000L).duration(50L * 1000L)
        .localEndpoint(backend)
        .remoteEndpoint(db).build()
    );

    processDependencies(trace);

    assertThat(store().getDependencies(TODAY + 1000, 1000L).execute()).containsOnly(
      DependencyLink.newBuilder()
        .parent(kafka.serviceName())
        .child(frontend.serviceName())
        .callCount(1)
        .build(),
      DependencyLink.newBuilder()
        .parent(frontend.serviceName())
        .child(backend.serviceName())
        .callCount(1)
        .build(),
      DependencyLink.newBuilder()
        .parent(backend.serviceName())
        .child(db.serviceName())
        .callCount(1)
        .build()
    );
  }

  @Test protected void unnamedEndpointsAreSkipped(TestInfo testInfo) throws Exception {
    String testSuffix = testSuffix(testInfo);
    String traceId = newTraceId();

    Endpoint frontend = suffixServiceName(TestObjects.FRONTEND, testSuffix);
    Endpoint backend = suffixServiceName(TestObjects.BACKEND, testSuffix);
    Endpoint db = suffixServiceName(TestObjects.DB, testSuffix);

    List<Span> trace = asList(
      Span.newBuilder().traceId(traceId).id("20").name("get")
        .timestamp(TODAY * 1000L).duration(350L * 1000L)
        .localEndpoint(Endpoint.newBuilder().ip("172.17.0.4").build())
        .remoteEndpoint(frontend).build(),
      Span.newBuilder().traceId(traceId).parentId("20").id("21").name("get")
        .timestamp((TODAY + 50) * 1000L).duration(250L * 1000L)
        .localEndpoint(frontend)
        .remoteEndpoint(backend).build(),
      Span.newBuilder().traceId(traceId).parentId("21").id("22").name("get")
        .timestamp((TODAY + 150) * 1000L).duration(50L * 1000L)
        .localEndpoint(backend)
        .remoteEndpoint(db).build()
    );

    processDependencies(trace);

    // note there is no empty string service names
    assertThat(store().getDependencies(TODAY + 1000, 1000L).execute()).containsOnly(
      DependencyLink.newBuilder()
        .parent(frontend.serviceName())
        .child(backend.serviceName())
        .callCount(1)
        .build(),
      DependencyLink.newBuilder()
        .parent(backend.serviceName())
        .child(db.serviceName())
        .callCount(1)
        .build()
    );
  }

  /**
   * This test confirms that the span store can process trace with intermediate spans like the below
   * properly.
   * <p>
   * span1: SR SS span2: intermediate call span3: CS SR SS CR: Dependency 1
   */
  @Test protected void intermediateSpans(TestInfo testInfo) throws Exception {
    String testSuffix = testSuffix(testInfo);
    String traceId = newTraceId();

    Endpoint frontend = suffixServiceName(TestObjects.FRONTEND, testSuffix);
    Endpoint backend = suffixServiceName(TestObjects.BACKEND, testSuffix);
    Endpoint db = suffixServiceName(TestObjects.DB, testSuffix);

    List<Span> trace = asList(
      Span.newBuilder().traceId(traceId).id("20").name("get")
        .timestamp(TODAY * 1000L).duration(350L * 1000L)
        .kind(Kind.SERVER)
        .localEndpoint(frontend).build(),
      Span.newBuilder().traceId(traceId).parentId("20").id("21").name("call")
        .timestamp((TODAY + 25) * 1000L).duration(325L * 1000L)
        .localEndpoint(frontend).build(),
      Span.newBuilder().traceId(traceId).parentId("21").id("22").name("get")
        .timestamp((TODAY + 50) * 1000L).duration(250L * 1000L)
        .kind(Kind.CLIENT)
        .localEndpoint(frontend).build(),
      Span.newBuilder().traceId(traceId).parentId("21").id("22").name("get")
        .timestamp((TODAY + 100) * 1000L).duration(150 * 1000L).shared(true)
        .kind(Kind.SERVER)
        .localEndpoint(backend).build(),
      Span.newBuilder().traceId(traceId).parentId("22").id(23L).name("call")
        .timestamp((TODAY + 110) * 1000L).duration(130L * 1000L)
        .name("depth4")
        .localEndpoint(backend).build(),
      Span.newBuilder().traceId(traceId).parentId(23L).id(24L).name("call")
        .timestamp((TODAY + 125) * 1000L).duration(105L * 1000L)
        .name("depth5")
        .localEndpoint(backend).build(),
      Span.newBuilder().traceId(traceId).parentId(24L).id(25L).name("get")
        .timestamp((TODAY + 150) * 1000L).duration(50L * 1000L)
        .kind(Kind.CLIENT)
        .localEndpoint(backend)
        .remoteEndpoint(db).build()
    );

    processDependencies(trace);

    assertThat(store().getDependencies(TODAY + 1000, 1000L).execute()).containsOnly(
      DependencyLink.newBuilder()
        .parent(frontend.serviceName())
        .child(backend.serviceName())
        .callCount(1)
        .build(),
      DependencyLink.newBuilder()
        .parent(backend.serviceName())
        .child(db.serviceName())
        .callCount(1)
        .build()
    );
  }

  /**
   * This test confirms that the span store can process trace with intermediate spans like the below
   * properly.
   * <p>
   * span1: SR SS span2: intermediate call span3: CS SR SS CR: Dependency 1
   */
  @Test protected void duplicateAddress(TestInfo testInfo) throws Exception {
    String testSuffix = testSuffix(testInfo);
    String traceId = newTraceId();

    Endpoint frontend = suffixServiceName(TestObjects.FRONTEND, testSuffix);
    Endpoint backend = suffixServiceName(TestObjects.BACKEND, testSuffix);

    V1SpanConverter converter = V1SpanConverter.create();
    List<Span> trace = new ArrayList<>();
    converter.convert(V1Span.newBuilder().traceId(traceId).id("20").name("get")
      .timestamp(TODAY * 1000L).duration(350L * 1000L)
      .addAnnotation(TODAY * 1000, "sr", frontend)
      .addAnnotation((TODAY + 350) * 1000, "ss", frontend)
      .addBinaryAnnotation("ca", frontend)
      .addBinaryAnnotation("sa", frontend).build(), trace);
    converter.convert(V1Span.newBuilder().traceId(traceId).parentId("21").id("22").name("get")
      .timestamp((TODAY + 50) * 1000L).duration(250L * 1000L)
      .addAnnotation((TODAY + 50) * 1000, "cs", frontend)
      .addAnnotation((TODAY + 300) * 1000, "cr", frontend)
      .addBinaryAnnotation("ca", backend)
      .addBinaryAnnotation("sa", backend).build(), trace);

    processDependencies(trace);

    assertThat(store().getDependencies(TODAY + 1000, 1000L).execute()).containsOnly(
      DependencyLink.newBuilder()
        .parent(frontend.serviceName())
        .child(backend.serviceName())
        .callCount(1)
        .build()
    );
  }

  /**
   * Span starts on one host and ends on the other. In both cases, a response is neither sent nor
   * received.
   */
  @Test protected void oneway(TestInfo testInfo) throws Exception {
    String testSuffix = testSuffix(testInfo);
    String traceId = newTraceId();

    Endpoint frontend = suffixServiceName(TestObjects.FRONTEND, testSuffix);
    Endpoint backend = suffixServiceName(TestObjects.BACKEND, testSuffix);

    List<Span> trace = asList(
      Span.newBuilder().traceId(traceId).id("10")
        .timestamp((TODAY + 50) * 1000)
        .kind(Kind.CLIENT)
        .localEndpoint(frontend)
        .build(),
      Span.newBuilder().traceId(traceId).id("10").shared(true)
        .timestamp((TODAY + 100) * 1000)
        .kind(Kind.SERVER)
        .localEndpoint(backend)
        .build()
    );

    processDependencies(trace);

    assertThat(store().getDependencies(endTs(trace), DAY).execute()).containsOnly(
      DependencyLink.newBuilder()
        .parent(frontend.serviceName())
        .child(backend.serviceName())
        .callCount(1)
        .build()
    );
  }

  /** A timeline annotation named error is not a failed span. A tag/binary annotation is. */
  @Test protected void annotationNamedErrorIsntError(TestInfo testInfo) throws Exception {
    String testSuffix = testSuffix(testInfo);
    String traceId = newTraceId();

    Endpoint frontend = suffixServiceName(TestObjects.FRONTEND, testSuffix);
    Endpoint backend = suffixServiceName(TestObjects.BACKEND, testSuffix);

    List<Span> trace = asList(
      Span.newBuilder().traceId(traceId).id("10")
        .timestamp((TODAY + 50) * 1000)
        .kind(Kind.CLIENT)
        .localEndpoint(frontend)
        .build(),
      Span.newBuilder().traceId(traceId).id("10").shared(true)
        .timestamp((TODAY + 100) * 1000)
        .kind(Kind.SERVER)
        .localEndpoint(backend)
        .addAnnotation((TODAY + 72) * 1000, "error")
        .build()
    );

    processDependencies(trace);

    assertThat(store().getDependencies(endTs(trace), DAY).execute()).containsOnly(
      DependencyLink.newBuilder()
        .parent(frontend.serviceName())
        .child(backend.serviceName())
        .callCount(1)
        .build()
    );
  }

  /** Async span starts from an uninstrumented source. */
  @Test protected void oneway_noClient(TestInfo testInfo) throws Exception {
    String testSuffix = testSuffix(testInfo);
    String traceId = newTraceId();

    Endpoint backend = suffixServiceName(TestObjects.BACKEND, testSuffix);
    Endpoint kafka = suffixServiceName(TestObjects.KAFKA, testSuffix);

    List<Span> trace = asList(
      Span.newBuilder().traceId(traceId).id("10").name("receive")
        .timestamp(TODAY * 1000)
        .kind(Kind.SERVER)
        .localEndpoint(backend)
        .remoteEndpoint(kafka)
        .build(),
      Span.newBuilder().traceId(traceId).parentId("10").id("11").name("process")
        .timestamp((TODAY + 25) * 1000L).duration(325L * 1000L)
        .localEndpoint(backend).build()
    );

    processDependencies(trace);

    assertThat(store().getDependencies(endTs(trace), DAY).execute()).containsOnly(
      DependencyLink.newBuilder()
        .parent(kafka.serviceName())
        .child(backend.serviceName())
        .callCount(1)
        .build()
    );
  }

  /** rebases a trace backwards a day with different trace. */
  List<Span> subtractDay(List<Span> trace) {
    long random = ThreadLocalRandom.current().nextLong();
    return trace.stream()
      .map(s -> {
          Span.Builder b = s.toBuilder().traceId(0L, random);
          if (s.timestampAsLong() != 0L) b.timestamp(s.timestampAsLong() - (DAY * 1000L));
          s.annotations().forEach(a -> b.addAnnotation(a.timestamp() - (DAY * 1000L), a.value()));
          return b.build();
        }
      ).collect(toList());
  }

  /** Returns links aggregated by midnight */
  public static Map<Long, List<DependencyLink>> aggregateLinks(List<Span> spans) {
    Map<Long, DependencyLinker> midnightToLinker = new LinkedHashMap<>();
    for (List<Span> trace : GroupByTraceId.create(false).map(spans)) {
      long midnightOfTrace = flooredTraceTimestamp(trace);
      DependencyLinker linker = midnightToLinker.get(midnightOfTrace);
      if (linker == null) midnightToLinker.put(midnightOfTrace, (linker = new DependencyLinker()));
      linker.putTrace(trace);
    }
    Map<Long, List<DependencyLink>> result = new LinkedHashMap<>();
    midnightToLinker.forEach((midnight, linker) -> result.put(midnight, linker.link()));
    return result;
  }

  /** Default links produced by {@link TestObjects#newTrace(String)} */
  static Iterable<? extends DependencyLink> links(String testSuffix) {
    String frontend = appendSuffix(TestObjects.FRONTEND.serviceName(), testSuffix);
    String backend = appendSuffix(TestObjects.BACKEND.serviceName(), testSuffix);
    String db = appendSuffix(TestObjects.DB.serviceName(), testSuffix);
    return asList(
      DependencyLink.newBuilder().parent(frontend).child(backend).callCount(1L).build(),
      DependencyLink.newBuilder().parent(backend).child(db).callCount(1L).errorCount(1L).build()
    );
  }

  /** gets the timestamp in milliseconds floored to midnight */
  static long flooredTraceTimestamp(List<Span> trace) {
    long midnightOfTrace = Long.MAX_VALUE;
    for (Span span : trace) {
      long currentTs = guessTimestamp(span);
      if (currentTs != 0L && currentTs < midnightOfTrace) {
        midnightOfTrace = midnightUTC(currentTs / 1000);
      }
    }
    assertThat(midnightOfTrace).isNotEqualTo(Long.MAX_VALUE);
    return midnightOfTrace;
  }

  static long guessTimestamp(Span span) {
    if (span.timestampAsLong() != 0L) return span.timestampAsLong();
    for (Annotation annotation : span.annotations()) {
      if (0L < annotation.timestamp()) {
        return annotation.timestamp();
      }
    }
    return 0L; // return a timestamp that won't match a query
  }
}
