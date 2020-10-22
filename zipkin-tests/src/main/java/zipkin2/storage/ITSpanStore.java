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

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import zipkin2.Call;
import zipkin2.Callback;
import zipkin2.Endpoint;
import zipkin2.Span;
import zipkin2.TestObjects;

import static java.util.Arrays.asList;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static zipkin2.Span.Kind.CLIENT;
import static zipkin2.TestObjects.DAY;
import static zipkin2.TestObjects.TODAY;
import static zipkin2.TestObjects.appendSuffix;
import static zipkin2.TestObjects.endTs;
import static zipkin2.TestObjects.newClientSpan;
import static zipkin2.TestObjects.newTrace;
import static zipkin2.TestObjects.newTraceId;
import static zipkin2.TestObjects.spanBuilder;
import static zipkin2.TestObjects.suffixServiceName;

/**
 * Base test for {@link SpanStore}.
 *
 * <p>Subtypes should create a connection to a real backend, even if that backend is in-process.
 */
public abstract class ITSpanStore<T extends StorageComponent> extends ITStorage<T> {

  @Override protected final void configureStorageForTest(StorageComponent.Builder storage) {
    // Defaults are fine.
  }

  /** This would only happen when the store layer is bootstrapping, or has been purged. */
  @Test protected void allShouldWorkWhenEmpty() throws Exception {
    QueryRequest.Builder q = requestBuilder().serviceName("service");
    assertGetTracesReturnsEmpty(q.build());
    assertGetTracesReturnsEmpty(q.remoteServiceName("remotey").build());
    assertGetTracesReturnsEmpty(q.spanName("methodcall").build());
    assertGetTracesReturnsEmpty(q.parseAnnotationQuery("custom").build());
    assertGetTracesReturnsEmpty(q.parseAnnotationQuery("BAH=BEH").build());
  }

  /** This is unlikely and means instrumentation sends empty spans by mistake. */
  @Test protected void allShouldWorkWhenNoIndexableDataYet() throws Exception {
    accept(Span.newBuilder().traceId(newTraceId()).id("1").build());

    allShouldWorkWhenEmpty();
  }

  @Test protected void consumer_implementsCall_execute(TestInfo testInfo) throws Exception {
    String testSuffix = testSuffix(testInfo);
    Span span = spanBuilder(testSuffix).build();

    Call<Void> call = storage.spanConsumer().accept(asList(span));

    // Ensure the implementation didn't accidentally do I/O at assembly time.
    assertGetTraceReturnsEmpty(span.traceId());
    call.execute();
    blockWhileInFlight();

    assertGetTraceReturns(span);

    assertThatThrownBy(call::execute)
      .isInstanceOf(IllegalStateException.class);

    // no problem to clone a call
    call.clone().execute();
  }

  @Test protected void consumer_implementsCall_submit(TestInfo testInfo) throws Exception {
    String testSuffix = testSuffix(testInfo);
    Span span = spanBuilder(testSuffix).build();

    Call<Void> call = storage.spanConsumer().accept(asList(span));
    // Ensure the implementation didn't accidentally do I/O at assembly time.
    assertGetTraceReturnsEmpty(span.traceId());

    CountDownLatch latch = new CountDownLatch(1);
    Callback<Void> callback = new Callback<Void>() {
      @Override public void onSuccess(Void value) {
        latch.countDown();
      }

      @Override public void onError(Throwable t) {
        latch.countDown();
      }
    };

    call.enqueue(callback);
    latch.await();
    blockWhileInFlight();

    assertGetTraceReturns(span);

    assertThatThrownBy(() -> call.enqueue(callback))
      .isInstanceOf(IllegalStateException.class);

    // no problem to clone a call
    call.clone().execute();
  }

  @Test protected void getTraces_groupsTracesTogether(TestInfo testInfo) throws Exception {
    String testSuffix = testSuffix(testInfo);
    Span traceASpan1 = spanBuilder(testSuffix).timestamp((TODAY + 1) * 1000L).build();
    Span traceASpan2 = traceASpan1.toBuilder().id("2").timestamp((TODAY + 2) * 1000L).build();

    String traceId2 = newTraceId();
    Span traceBSpan1 = traceASpan1.toBuilder().traceId(traceId2).build();
    Span traceBSpan2 = traceASpan2.toBuilder().traceId(traceId2).build();

    accept(traceASpan1, traceBSpan1, traceASpan2, traceBSpan2);

    assertGetTracesReturns(
      requestBuilder().build(),
      asList(traceASpan1, traceASpan2), asList(traceBSpan1, traceBSpan2)
    );
  }

  @Test protected void getTraces_considersBitsAbove64bit(TestInfo testInfo) throws Exception {
    String testSuffix = testSuffix(testInfo);
    String traceId = newTraceId();
    Endpoint frontend = suffixServiceName(TestObjects.FRONTEND, testSuffix);

    // 64-bit trace ID
    Span span1 = Span.newBuilder().traceId(traceId.substring(16)).id("1")
      .putTag("foo", "1")
      .timestamp(TODAY * 1000L)
      .localEndpoint(frontend)
      .build();
    // 128-bit trace ID prefixed by above
    Span span2 = span1.toBuilder().traceId(traceId).putTag("foo", "2").build();
    // Different 128-bit trace ID prefixed by above
    Span span3 = span1.toBuilder().traceId("1" + span1.traceId()).putTag("foo", "3").build();

    accept(span1, span2, span3);

    for (Span span : Arrays.asList(span1, span2, span3)) {
      assertGetTracesReturns(
        requestBuilder().serviceName(frontend.serviceName())
          .parseAnnotationQuery("foo=" + span.tags().get("foo"))
          .build(),
        asList(span));
    }
  }

  @Test protected void getTraces_filteringMatchesMostRecentTraces(TestInfo testInfo)
    throws Exception {
    String testSuffix = testSuffix(testInfo);
    List<Endpoint> endpoints = IntStream.rangeClosed(1, 10)
      .mapToObj(i -> Endpoint.newBuilder()
        .serviceName(appendSuffix("service" + i, testSuffix))
        .ip("127.0.0.1")
        .build())
      .collect(Collectors.toList());

    long gapBetweenSpans = 100;
    Span[] earlySpans = IntStream.rangeClosed(1, 10).mapToObj(i -> Span.newBuilder().name("early")
      .traceId(newTraceId()).id(Integer.toHexString(i))
      .timestamp((TODAY - i) * 1000L).duration(1L)
      .localEndpoint(endpoints.get(i - 1)).build()).toArray(Span[]::new);

    Span[] lateSpans = IntStream.rangeClosed(1, 10).mapToObj(i -> Span.newBuilder().name("late")
      .traceId(newTraceId()).id(Integer.toHexString(i + 10))
      .timestamp((TODAY + gapBetweenSpans - i) * 1000L).duration(1L)
      .localEndpoint(endpoints.get(i - 1)).build()).toArray(Span[]::new);

    accept(earlySpans);
    accept(lateSpans);

    List<Span>[] earlyTraces =
      Stream.of(earlySpans).map(Collections::singletonList).toArray(List[]::new);
    List<Span>[] lateTraces =
      Stream.of(lateSpans).map(Collections::singletonList).toArray(List[]::new);

    assertGetTracesReturnsCount(requestBuilder().build(), 20);

    assertGetTracesReturns(
      requestBuilder().limit(10).build(),
      lateTraces);

    assertGetTracesReturns(
      requestBuilder().endTs(TODAY + gapBetweenSpans).lookback(gapBetweenSpans).build(),
      lateTraces);

    assertGetTracesReturns(
      requestBuilder().endTs(TODAY).build(),
      earlyTraces);
  }

  @Test protected void getTraces_serviceNames(TestInfo testInfo) throws Exception {
    String testSuffix = testSuffix(testInfo);
    getTraces_serviceNames(newClientSpan(testSuffix));
  }

  void getTraces_serviceNames(Span clientSpan) throws Exception {
    accept(clientSpan);

    assertGetTracesReturnsEmpty(
      requestBuilder().serviceName(clientSpan.localServiceName() + 1).build());

    assertGetTracesReturns(
      requestBuilder().serviceName(clientSpan.localServiceName()).build(),
      asList(clientSpan));

    assertGetTracesReturnsEmpty(
      requestBuilder()
        .serviceName(clientSpan.localServiceName())
        .remoteServiceName(clientSpan.remoteServiceName() + 1)
        .build());

    assertGetTracesReturns(
      requestBuilder()
        .serviceName(clientSpan.localServiceName())
        .remoteServiceName(clientSpan.remoteServiceName())
        .build(),
      asList(clientSpan));
  }

  @Test protected void getTraces_serviceNames_mixedTraceIdLength(TestInfo testInfo)
    throws Exception {
    String testSuffix = testSuffix(testInfo);
    Span clientSpan = newClientSpan(testSuffix);

    // add a trace with the same trace ID truncated to 64 bits, except different service names.
    accept(spanBuilder(testSuffix)
      .traceId(clientSpan.traceId().substring(16))
      .localEndpoint(Endpoint.newBuilder().serviceName(appendSuffix("foo", testSuffix)).build())
      .remoteEndpoint(Endpoint.newBuilder().serviceName(appendSuffix("bar", testSuffix)).build())
      .build());

    getTraces_serviceNames(clientSpan);
  }

  @Test protected void getTraces_spanName(TestInfo testInfo) throws Exception {
    String testSuffix = testSuffix(testInfo);
    getTraces_spanName(newClientSpan(testSuffix));
  }

  void getTraces_spanName(Span clientSpan) throws Exception {
    accept(clientSpan);

    assertGetTracesReturnsEmpty(
      requestBuilder().spanName(clientSpan.name() + 1).build());

    assertGetTracesReturnsEmpty(
      requestBuilder()
        .serviceName(clientSpan.localServiceName())
        .spanName(clientSpan.name() + 1)
        .build());

    assertGetTracesReturns(
      requestBuilder().spanName(clientSpan.name()).build(),
      asList(clientSpan));

    assertGetTracesReturns(
      requestBuilder()
        .serviceName(clientSpan.localServiceName())
        .spanName(clientSpan.name())
        .build(),
      asList(clientSpan));
  }

  @Test protected void getTraces_spanName_mixedTraceIdLength(TestInfo testInfo) throws Exception {
    String testSuffix = testSuffix(testInfo);
    Span clientSpan = newClientSpan(testSuffix);

    // add a trace with the same trace ID truncated to 64 bits, except the span name.
    accept(clientSpan.toBuilder()
      .traceId(clientSpan.traceId().substring(16))
      .name("bar")
      .build());

    getTraces_spanName(clientSpan);
  }

  @Test protected void getTraces_tags(TestInfo testInfo) throws Exception {
    String testSuffix = testSuffix(testInfo);
    Span clientSpan = newClientSpan(testSuffix);

    accept(clientSpan);

    assertGetTracesReturnsEmpty(
      requestBuilder().annotationQuery(Collections.singletonMap("foo", "bar")).build());

    assertGetTracesReturns(
      requestBuilder().annotationQuery(clientSpan.tags()).build(),
      asList(clientSpan));
  }

  @Test protected void getTraces_minDuration(TestInfo testInfo) throws Exception {
    String testSuffix = testSuffix(testInfo);
    Span clientSpan = newClientSpan(testSuffix);

    accept(clientSpan);

    assertGetTracesReturnsEmpty(
      requestBuilder().minDuration(clientSpan.durationAsLong() + 1).build());

    assertGetTracesReturns(
      requestBuilder().minDuration(clientSpan.durationAsLong()).build(),
      asList(clientSpan));
  }

  // pretend we had a late update of only timestamp/duration info
  @Test protected void getTraces_lateDuration(TestInfo testInfo) throws Exception {
    String testSuffix = testSuffix(testInfo);
    Span clientSpan = newClientSpan(testSuffix);
    Span missingDuration = clientSpan.toBuilder().duration(0L).build();
    Span lateDuration = Span.newBuilder()
      .traceId(clientSpan.traceId())
      .id(clientSpan.id())
      .timestamp(clientSpan.timestampAsLong())
      .duration(clientSpan.durationAsLong())
      .localEndpoint(clientSpan.localEndpoint())
      .build();
    accept(missingDuration);
    accept(lateDuration);

    assertGetTracesReturnsEmpty(
      requestBuilder().minDuration(clientSpan.durationAsLong() + 1).build());

    assertGetTracesReturns(
      requestBuilder().minDuration(clientSpan.durationAsLong()).build(),
      asList(lateDuration, missingDuration));
  }

  @Test protected void getTraces_maxDuration(TestInfo testInfo) throws Exception {
    String testSuffix = testSuffix(testInfo);
    Span clientSpan = newClientSpan(testSuffix);

    accept(clientSpan);

    assertGetTracesReturnsEmpty(
      requestBuilder()
        .minDuration(clientSpan.durationAsLong() - 2)
        .maxDuration(clientSpan.durationAsLong() - 1)
        .build());

    assertGetTracesReturns(
      requestBuilder()
        .minDuration(clientSpan.durationAsLong())
        .maxDuration(clientSpan.durationAsLong())
        .build(),
      asList(clientSpan));
  }

  /**
   * The following skeletal span is used in dependency linking.
   *
   * <p>Notably this guards empty tag values work
   */
  @Test protected void readback_minimalErrorSpan(TestInfo testInfo) throws Exception {
    String testSuffix = testSuffix(testInfo);
    String serviceName = appendSuffix("isao01", testSuffix);
    Span errorSpan = Span.newBuilder().traceId(newTraceId()).id("1")
      .timestamp(TODAY * 1000L)
      .localEndpoint(Endpoint.newBuilder().serviceName(serviceName).build())
      .kind(CLIENT)
      .putTag("error", "")
      .build();
    accept(errorSpan);

    QueryRequest.Builder requestBuilder =
      requestBuilder().serviceName(serviceName); // so this doesn't die on cassandra v1

    assertGetTracesReturns(requestBuilder.build(), asList(errorSpan));

    assertGetTracesReturns(
      requestBuilder.parseAnnotationQuery("error").build(), asList(errorSpan));

    assertGetTracesReturnsEmpty(
      requestBuilder.parseAnnotationQuery("error=1").build());

    assertGetTraceReturns(errorSpan);
  }

  /**
   * While large spans are discouraged, and maybe not indexed, we should be able to read them back.
   */
  @Test protected void readsBackLargeValues(TestInfo testInfo) throws Exception {
    String testSuffix = testSuffix(testInfo);
    char[] kilobyteOfText = new char[1024];
    Arrays.fill(kilobyteOfText, 'a');

    // Make a span that's over 1KiB in size
    Span span = spanBuilder(testSuffix).name("big").putTag("a", new String(kilobyteOfText)).build();

    accept(span);

    // read back to ensure the data wasn't truncated
    assertGetTracesReturns(requestBuilder().build(), asList(span));
    assertGetTraceReturns(span);
  }

  /**
   * This tests problematic data that can sometimes break storage:
   *
   * <ul>
   *   <li>json in span name</li>
   *   <li>tag with nested dots (can be confused as nested objects)</li>
   * </ul>
   */
  @Test protected void spanWithProblematicData(TestInfo testInfo) throws Exception {
    String testSuffix = testSuffix(testInfo);
    // Intentionally store in two fragments to try to trigger storage problems with dots
    Span part1 = spanBuilder(testSuffix)
      .putTag("http.path", "/api")
      .build();
    accept(part1);

    String json = "{\"foo\":\"bar\"}";
    Span part2 = part1.toBuilder()
      .name(json)
      .clearTags()
      .putTag("http.path.morepath", "/api/api")
      .build();
    accept(part2);

    assertGetTracesReturns(
      requestBuilder().serviceName(part1.localServiceName()).spanName(json).build(),
      asList(part2, part1)
    );

    assertGetTraceReturns(part1.traceId(), asList(part2, part1));
  }

  /** Shows that duration queries go against the root span, not the child */
  @Test protected void getTraces_duration(TestInfo testInfo) throws Exception {
    String testSuffix = testSuffix(testInfo);
    Endpoint frontend = suffixServiceName(TestObjects.FRONTEND, testSuffix);
    Endpoint backend = suffixServiceName(TestObjects.BACKEND, testSuffix);
    Endpoint db = suffixServiceName(TestObjects.DB, testSuffix);

    List<List<Span>> traces = setupDurationData(testInfo);
    List<Span> trace1 = traces.get(0), trace2 = traces.get(1), trace3 = traces.get(2);

    QueryRequest.Builder q = requestBuilder().endTs(TODAY).lookback(DAY); // instead of since epoch

    // Min duration is inclusive and is applied by service.
    assertGetTracesReturns(
      q.serviceName(frontend.serviceName()).minDuration(200_000L).build(),
      trace1);

    assertGetTracesReturns(
      q.serviceName(db.serviceName()).minDuration(200_000L).build(),
      trace2);

    // Duration bounds aren't limited to root spans: they apply to all spans by service in a trace
    assertGetTracesReturns(
      q.serviceName(backend.serviceName())
        .minDuration(50_000L)
        .maxDuration(150_000L)
        .build(),
      trace1, trace2, trace3);

    // Remote service name should apply to the duration filter
    assertGetTracesReturns(
      q.serviceName(frontend.serviceName())
        .remoteServiceName(backend.serviceName())
        .maxDuration(50_000L)
        .build(),
      trace2);

    // Span name should apply to the duration filter
    assertGetTracesReturns(
      q.serviceName(backend.serviceName()).spanName("zip").maxDuration(50_000L).build(),
      trace3);

    // Max duration should filter our longer spans from the same service
    assertGetTracesReturns(
      q.serviceName(backend.serviceName())
        .minDuration(50_000L)
        .maxDuration(50_000L)
        .build(),
      trace3);
  }

  /**
   * Spans and traces are meaningless unless they have a timestamp. While unlikely, this could
   * happen if a binary annotation is logged before a timestamped one is.
   */
  @Test protected void getTraces_absentWhenNoTimestamp(TestInfo testInfo) throws Exception {
    String testSuffix = testSuffix(testInfo);
    Span span = spanBuilder(testSuffix).build();
    Span spanWithoutTimestamp = span.toBuilder().timestamp(0L).duration(0L).build();

    // Index the service name but no timestamp of any sort
    accept(spanWithoutTimestamp);

    assertGetTracesReturnsEmpty(
      requestBuilder().serviceName(span.localServiceName()).build());

    assertGetTracesReturnsEmpty(
      requestBuilder().serviceName(span.localServiceName())
        .spanName(span.remoteServiceName())
        .build());

    assertGetTracesReturnsEmpty(
      requestBuilder().serviceName(span.localServiceName())
        .spanName(span.name())
        .build());

    // now store the timestamped span
    accept(span);

    assertGetTracesReturns(
      requestBuilder().serviceName(span.localServiceName()).build(),
      asList(spanWithoutTimestamp, span));

    assertGetTracesReturns(
      requestBuilder()
        .serviceName(span.localServiceName())
        .remoteServiceName(span.remoteServiceName())
        .build(),
      asList(spanWithoutTimestamp, span));

    assertGetTracesReturns(
      requestBuilder()
        .serviceName(span.localServiceName())
        .spanName(span.name())
        .build(),
      asList(spanWithoutTimestamp, span));
  }

  @Test protected void getTraces_annotation(TestInfo testInfo) throws Exception {
    String testSuffix = testSuffix(testInfo);
    Span clientSpan = newClientSpan(testSuffix).toBuilder()
      .addAnnotation(TODAY, "foo")
      .build();

    accept(clientSpan);

    // fetch by time based annotation, find trace
    assertGetTracesReturns(
      requestBuilder()
        .serviceName(clientSpan.localServiceName())
        .parseAnnotationQuery(clientSpan.annotations().get(0).value())
        .build(),
      asList(clientSpan));

    // should find traces by a tag
    Map.Entry<String, String> tag = clientSpan.tags().entrySet().iterator().next();
    assertGetTracesReturns(
      requestBuilder()
        .serviceName(clientSpan.localServiceName())
        .parseAnnotationQuery(tag.getKey() + "=" + tag.getValue())
        .build(),
      asList(clientSpan));
  }

  @Test
  protected void getTraces_multipleAnnotationsBecomeAndFilter(TestInfo testInfo) throws Exception {
    String testSuffix = testSuffix(testInfo);
    Endpoint frontend = suffixServiceName(TestObjects.FRONTEND, testSuffix);

    Span foo = Span.newBuilder().traceId(newTraceId()).name("call1").id(1)
      .timestamp((TODAY + 1) * 1000L)
      .localEndpoint(frontend)
      .addAnnotation((TODAY + 1) * 1000L, "foo").build();
    // would be foo bar, except lexicographically bar precedes foo
    Span barAndFoo = Span.newBuilder().traceId(newTraceId()).name("call2").id(2)
      .timestamp((TODAY + 2) * 1000L)
      .localEndpoint(frontend)
      .addAnnotation((TODAY + 2) * 1000L, "bar")
      .addAnnotation((TODAY + 2) * 1000L, "foo").build();
    Span fooAndBazAndQux = Span.newBuilder().traceId(newTraceId()).name("call3").id(3)
      .timestamp((TODAY + 3) * 1000L)
      .localEndpoint(frontend)
      .addAnnotation((TODAY + 3) * 1000L, "foo")
      .putTag("baz", "qux")
      .build();
    Span barAndFooAndBazAndQux = Span.newBuilder().traceId(newTraceId()).name("call4").id(4)
      .timestamp((TODAY + 4) * 1000L)
      .localEndpoint(frontend)
      .addAnnotation((TODAY + 4) * 1000L, "bar")
      .addAnnotation((TODAY + 4) * 1000L, "foo")
      .putTag("baz", "qux")
      .build();

    accept(foo, barAndFoo, fooAndBazAndQux, barAndFooAndBazAndQux);

    assertGetTracesReturns(
      requestBuilder().serviceName(frontend.serviceName()).parseAnnotationQuery("foo").build(),
      asList(foo), asList(barAndFoo), asList(fooAndBazAndQux), asList(barAndFooAndBazAndQux)
    );

    assertGetTracesReturns(
      requestBuilder().serviceName(frontend.serviceName())
        .parseAnnotationQuery("foo and bar")
        .build(),
      asList(barAndFoo), asList(barAndFooAndBazAndQux)
    );

    assertGetTracesReturns(
      requestBuilder().serviceName(frontend.serviceName())
        .parseAnnotationQuery("foo and bar and baz=qux")
        .build(),
      asList(barAndFooAndBazAndQux));

    // ensure we can search only by tag key
    assertGetTracesReturns(
      requestBuilder().serviceName(frontend.serviceName()).parseAnnotationQuery("baz").build(),
      asList(fooAndBazAndQux), asList(barAndFooAndBazAndQux)
    );
  }

  /** This test makes sure that annotation queries pay attention to which host recorded data */
  @Test protected void getTraces_differentiateOnServiceName(TestInfo testInfo) throws Exception {
    String testSuffix = testSuffix(testInfo);
    Endpoint frontend = suffixServiceName(TestObjects.FRONTEND, testSuffix);
    Endpoint backend = suffixServiceName(TestObjects.BACKEND, testSuffix);

    Span trace1 = Span.newBuilder().traceId(newTraceId()).name("1").id(1)
      .kind(CLIENT)
      .timestamp((TODAY + 1) * 1000L)
      .duration(3000L)
      .localEndpoint(frontend)
      .addAnnotation(((TODAY + 1) * 1000L) + 500, "web")
      .putTag("local", "web")
      .putTag("web-b", "web")
      .build();

    Span trace1Server = Span.newBuilder().traceId(trace1.traceId()).name("1").id(1)
      .kind(Span.Kind.SERVER)
      .shared(true)
      .localEndpoint(backend)
      .timestamp((TODAY + 2) * 1000L)
      .duration(1000L)
      .build();

    Span trace2 = Span.newBuilder().traceId(newTraceId()).name("2").id(2)
      .timestamp((TODAY + 11) * 1000L)
      .duration(3000L)
      .kind(CLIENT)
      .localEndpoint(backend)
      .addAnnotation(((TODAY + 11) * 1000) + 500, "app")
      .putTag("local", "app")
      .putTag("app-b", "app")
      .build();

    Span trace2Server = Span.newBuilder().traceId(trace2.traceId()).name("2").id(2)
      .shared(true)
      .kind(Span.Kind.SERVER)
      .localEndpoint(frontend)
      .timestamp((TODAY + 12) * 1000L)
      .duration(1000L).build();

    accept(trace1, trace1Server, trace2, trace2Server);

    // Sanity check
    assertGetTraceReturns(trace1.traceId(), asList(trace1, trace1Server));
    assertGetTraceReturns(trace2.traceId(), asList(trace2, trace2Server));
    assertGetTracesReturns(requestBuilder().build(),
      asList(trace1, trace1Server), asList(trace2, trace2Server));

    // We only return traces where the service specified caused the data queried.
    assertGetTracesReturns(
      requestBuilder().serviceName(frontend.serviceName()).parseAnnotationQuery("web").build(),
      asList(trace1, trace1Server));

    assertGetTracesReturnsEmpty(
      requestBuilder().serviceName(backend.serviceName()).parseAnnotationQuery("web").build());

    assertGetTracesReturns(
      requestBuilder().serviceName(backend.serviceName()).parseAnnotationQuery("app").build(),
      asList(trace2, trace2Server));

    assertGetTracesReturnsEmpty(
      requestBuilder().serviceName(frontend.serviceName()).parseAnnotationQuery("app").build());

    // tags are returned on annotation queries
    assertGetTracesReturns(
      requestBuilder().serviceName(frontend.serviceName()).parseAnnotationQuery("web-b").build(),
      asList(trace1, trace1Server));

    assertGetTracesReturnsEmpty(
      requestBuilder().serviceName(backend.serviceName()).parseAnnotationQuery("web-b").build());

    assertGetTracesReturns(
      requestBuilder().serviceName(backend.serviceName()).parseAnnotationQuery("app-b").build(),
      asList(trace2, trace2Server));

    assertGetTracesReturnsEmpty(
      requestBuilder().serviceName(frontend.serviceName()).parseAnnotationQuery("app-b").build());

    // We only return traces where the service specified caused the tag queried.
    assertGetTracesReturns(
      requestBuilder().serviceName(frontend.serviceName())
        .parseAnnotationQuery("local=web")
        .build(),
      asList(trace1, trace1Server));

    assertGetTracesReturnsEmpty(
      requestBuilder().serviceName(backend.serviceName())
        .parseAnnotationQuery("local=web")
        .build());

    assertGetTracesReturns(
      requestBuilder().serviceName(backend.serviceName()).parseAnnotationQuery("local=app").build(),
      asList(trace2, trace2Server));

    assertGetTracesReturnsEmpty(
      requestBuilder().serviceName(frontend.serviceName())
        .parseAnnotationQuery("local=app")
        .build());
  }

  /** limit should apply to traces closest to endTs */
  @Test protected void getTraces_limit(TestInfo testInfo) throws Exception {
    String testSuffix = testSuffix(testInfo);
    Span span1 = spanBuilder(testSuffix).build();
    Span span2 = span1.toBuilder().traceId(newTraceId()).timestamp((TODAY + 2) * 1000L).build();
    accept(span1, span2);

    assertGetTracesReturns(
      requestBuilder().serviceName(span1.localServiceName()).limit(1).build(),
      asList(span2));
  }

  /** Traces whose root span has timestamps between (endTs - lookback) and endTs are returned */
  @Test protected void getTraces_endTsAndLookback(TestInfo testInfo) throws Exception {
    String testSuffix = testSuffix(testInfo);
    Span span1 = spanBuilder(testSuffix).timestamp((TODAY + 1) * 1000L).build();
    Span span2 = span1.toBuilder().traceId(newTraceId()).timestamp((TODAY + 2) * 1000L).build();
    accept(span1, span2);

    assertGetTracesReturnsEmpty(
      requestBuilder().endTs(TODAY).build());

    assertGetTracesReturns(
      requestBuilder().endTs(TODAY + 1).build(),
      asList(span1));

    assertGetTracesReturns(
      requestBuilder().endTs(TODAY + 2).build(),
      asList(span1), asList(span2));

    assertGetTracesReturns(
      requestBuilder().endTs(TODAY + 3).build(),
      asList(span1), asList(span2));

    assertGetTracesReturnsEmpty(
      requestBuilder().endTs(TODAY).build());

    assertGetTracesReturns(
      requestBuilder().endTs(TODAY + 1).lookback(1).build(),
      asList(span1));

    assertGetTracesReturns(
      requestBuilder().endTs(TODAY + 2).lookback(1).build(),
      asList(span1), asList(span2));

    assertGetTracesReturns(
      requestBuilder().endTs(TODAY + 3).lookback(1).build(),
      asList(span2));
  }

  @Test protected void names_goLowercase(TestInfo testInfo) throws Exception {
    String testSuffix = testSuffix(testInfo);
    Span clientSpan = newClientSpan(testSuffix);

    accept(clientSpan);

    assertGetTracesReturns(
      requestBuilder()
        .serviceName(clientSpan.localServiceName())
        .remoteServiceName(clientSpan.remoteServiceName().toUpperCase(Locale.ROOT))
        .build(),
      asList(clientSpan));

    assertGetTracesReturns(
      requestBuilder()
        .serviceName(clientSpan.localServiceName())
        .spanName(clientSpan.name().toUpperCase(Locale.ROOT)).build(),
      asList(clientSpan));

    assertGetTracesReturns(
      requestBuilder()
        .serviceName(clientSpan.localServiceName())
        .remoteServiceName(clientSpan.remoteServiceName().toUpperCase(Locale.ROOT))
        .build(),
      asList(clientSpan));
  }

  /** Ensure complete traces are aggregated, even if they complete after endTs */
  @Test protected void getTraces_endTsInsideTheTrace(TestInfo testInfo) throws Exception {
    String testSuffix = testSuffix(testInfo);
    List<Span> trace = newTrace(testSuffix);

    accept(trace);

    //  - Note: Using the smallest lookback avoids bumping into implementation around windowing.
    long lookback = trace.get(0).durationAsLong() / 1000L;
    assertGetTracesReturns(
      requestBuilder().endTs(endTs(trace)).lookback(lookback).build(),
      trace);
  }

  List<List<Span>> setupDurationData(TestInfo testInfo) throws Exception {
    String testSuffix = testSuffix(testInfo);
    Endpoint frontend = suffixServiceName(TestObjects.FRONTEND, testSuffix);
    Endpoint backend = suffixServiceName(TestObjects.BACKEND, testSuffix);
    Endpoint db = suffixServiceName(TestObjects.DB, testSuffix);

    String traceId1 = newTraceId(), traceId2 = newTraceId(), traceId3 = newTraceId();
    long offsetMicros = (TODAY - 3) * 1000L; // to make sure queries look back properly
    Span targz = Span.newBuilder().traceId(traceId1).id(1L)
      .name("targz").timestamp(offsetMicros + 100L).duration(200_000L)
      .localEndpoint(frontend)
      .remoteEndpoint(db)
      .putTag("lc", "archiver").build();
    Span tar = Span.newBuilder().traceId(traceId1).id(2L).parentId(1L)
      .name("tar").timestamp(offsetMicros + 200L).duration(150_000L)
      .localEndpoint(backend)
      .remoteEndpoint(backend)
      .putTag("lc", "archiver").build();
    Span gz = Span.newBuilder().traceId(traceId1).id(3L).parentId(1L)
      .name("gz").timestamp(offsetMicros + 250L).duration(50_000L)
      .localEndpoint(db)
      .remoteEndpoint(frontend)
      .putTag("lc", "archiver").build();
    Span zip = Span.newBuilder().traceId(traceId3).id(3L)
      .name("zip").timestamp(offsetMicros + 130L).duration(50_000L)
      .addAnnotation(offsetMicros + 130L, "zip")
      .localEndpoint(backend)
      .remoteEndpoint(backend)
      .putTag("lc", "archiver").build();

    List<Span> trace1 = asList(targz, tar, gz);
    List<Span> trace2 = asList(
      targz.toBuilder().traceId(traceId2).timestamp(offsetMicros + 110L)
        .localEndpoint(db)
        .remoteEndpoint(frontend)
        .putTag("lc", "archiver-v2").build(),
      tar.toBuilder().traceId(traceId2).timestamp(offsetMicros + 210L)
        .localEndpoint(backend)
        .remoteEndpoint(backend)
        .putTag("lc", "archiver").build(),
      gz.toBuilder().traceId(traceId2).timestamp(offsetMicros + 260L)
        .localEndpoint(frontend)
        .remoteEndpoint(backend)
        .putTag("lc", "archiver").build());
    List<Span> trace3 = asList(zip);

    accept(trace1);
    accept(trace2);
    accept(trace3);
    return asList(trace1, trace2, trace3);
  }
}
