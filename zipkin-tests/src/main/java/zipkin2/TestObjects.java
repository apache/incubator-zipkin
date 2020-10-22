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
package zipkin2;

import java.nio.charset.Charset;
import java.util.Calendar;
import java.util.List;
import java.util.Random;
import java.util.TimeZone;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import zipkin2.Span.Kind;
import zipkin2.internal.WriteBuffer;

import static java.util.Arrays.asList;
import static zipkin2.Span.Kind.CLIENT;

public final class TestObjects {
  public static final Charset UTF_8 = Charset.forName("UTF-8");
  /** Notably, the cassandra implementation has day granularity */
  public static final long DAY = TimeUnit.MILLISECONDS.convert(1, TimeUnit.DAYS);

  static final TimeZone UTC = TimeZone.getTimeZone("UTC");

  // Use real time, as most span-stores have TTL logic which looks back several days.
  public static final long TODAY = midnightUTC(System.currentTimeMillis());

  public static final Endpoint FRONTEND =
    Endpoint.newBuilder().serviceName("frontend").ip("127.0.0.1").build();
  public static final Endpoint BACKEND =
    Endpoint.newBuilder().serviceName("backend").ip("192.168.99.101").port(9000).build();
  public static final Endpoint DB =
    Endpoint.newBuilder().serviceName("db").ip("2001:db8::c001").port(3036).build();
  public static final Endpoint KAFKA = Endpoint.newBuilder().serviceName("kafka").build();

  /** For bucketed data floored to the day. For example, dependency links. */
  public static long midnightUTC(long epochMillis) {
    Calendar day = Calendar.getInstance(UTC);
    day.setTimeInMillis(epochMillis);
    day.set(Calendar.MILLISECOND, 0);
    day.set(Calendar.SECOND, 0);
    day.set(Calendar.MINUTE, 0);
    day.set(Calendar.HOUR_OF_DAY, 0);
    return day.getTimeInMillis();
  }

  /** Only for unit tests, not integration tests. Integration tests should use random trace IDs. */
  public static final Span CLIENT_SPAN = Span.newBuilder()
    .traceId("7180c278b62e8f6a216a2aea45d08fc9")
    .parentId("1")
    .id("2")
    .name("get")
    .kind(Kind.CLIENT)
    .localEndpoint(FRONTEND)
    .remoteEndpoint(BACKEND)
    .timestamp((TODAY + 50L) * 1000L)
    .duration(200 * 1000L)
    .addAnnotation((TODAY + 100) * 1000L, "foo")
    .putTag("http.path", "/api")
    .putTag("clnt/finagle.version", "6.45.0")
    .build();

  /** Only for unit tests, not integration tests. Integration tests should use random trace IDs. */
  public static final List<Span> TRACE = newTrace(CLIENT_SPAN.traceId(), "");

  // storage query units are milliseconds, while trace data is microseconds
  public static long startTs(List<Span> trace) {
    return trace.get(0).timestampAsLong() / 1000L;
  }

  public static long endTs(List<Span> trace) {
    return startTs(trace) + trace.get(0).durationAsLong() / 1000L;
  }

  static final Span.Builder SPAN_BUILDER = newSpanBuilder();

  /** Reuse a builder as it is significantly slows tests to create 100000 of these! */
  static Span.Builder newSpanBuilder() {
    return Span.newBuilder().name("get")
      .timestamp(TODAY * 1000L + 100L)
      .duration(200 * 1000L)
      .localEndpoint(FRONTEND)
      .putTag("environment", "test");
  }

  /**
   * Zipkin trace ids are random 64bit numbers. This creates a relatively large input to avoid
   * flaking out due to PRNG nuance.
   */
  public static final Span[] LOTS_OF_SPANS =
    new Random().longs(100_000).mapToObj(TestObjects::span).toArray(Span[]::new);

  public static Span span(long traceId) {
    return SPAN_BUILDER.traceId(0L, traceId).id(traceId).build();
  }

  public static Span newClientSpan(String serviceNameSuffix) {
    return spanBuilder(serviceNameSuffix).kind(CLIENT)
      .remoteEndpoint(BACKEND.toBuilder().serviceName("backend" + serviceNameSuffix).build())
      .name("get /foo")
      .clearTags()
      .putTag("http.method", "GET")
      .putTag("http.path", "/foo")
      .build();
  }

  public static Span.Builder spanBuilder(String serviceNameSuffix) {
    Endpoint frontend = suffixServiceName(FRONTEND, serviceNameSuffix);
    return SPAN_BUILDER.clone().localEndpoint(frontend).traceId(newTraceId());
  }

  public static String appendSuffix(String serviceName, String serviceNameSuffix) {
    if (serviceNameSuffix == null) throw new NullPointerException("serviceNameSuffix == null");
    if (serviceNameSuffix.isEmpty()) return serviceName;
    return serviceName + "_" + serviceNameSuffix;
  }

  public static Endpoint suffixServiceName(Endpoint endpoint, String serviceNameSuffix) {
    String prefixed = appendSuffix(endpoint.serviceName(), serviceNameSuffix);
    if (endpoint.serviceName().equals(prefixed)) return endpoint;
    return endpoint.toBuilder().serviceName(prefixed).build();
  }

  public static List<Span> newTrace(String serviceNameSuffix) {
    return newTrace(newTraceId(), serviceNameSuffix);
  }

  static List<Span> newTrace(String traceId, String serviceNameSuffix) {
    Endpoint frontend = suffixServiceName(FRONTEND, serviceNameSuffix);
    Endpoint backend = suffixServiceName(BACKEND, serviceNameSuffix);
    Endpoint db = suffixServiceName(DB, serviceNameSuffix);

    return asList(
      Span.newBuilder().traceId(traceId).id("1")
        .name("get")
        .kind(Kind.SERVER)
        .localEndpoint(frontend)
        .timestamp(TODAY * 1000L)
        .duration(350 * 1000L)
        .build(),
      CLIENT_SPAN.toBuilder()
        .traceId(traceId)
        .localEndpoint(frontend)
        .remoteEndpoint(backend)
        .build(),
      Span.newBuilder().traceId(traceId)
        .parentId(CLIENT_SPAN.parentId()).id(CLIENT_SPAN.id()).shared(true)
        .name("get")
        .kind(Kind.SERVER)
        .localEndpoint(backend)
        .timestamp((TODAY + 100L) * 1000L)
        .duration(150 * 1000L)
        .build(),
      Span.newBuilder().traceId(traceId).parentId("2").id("3")
        .name("query")
        .kind(Kind.CLIENT)
        .localEndpoint(backend)
        .remoteEndpoint(db)
        .timestamp((TODAY + 150L) * 1000L)
        .duration(50 * 1000L)
        .addAnnotation((TODAY + 190) * 1000L, "⻩")
        .putTag("error", "\uD83D\uDCA9")
        .build()
    );
  }

  public static String newTraceId() {
    byte[] traceId = new byte[32];
    WriteBuffer buffer = WriteBuffer.wrap(traceId);
    buffer.writeLongHex(ThreadLocalRandom.current().nextLong());
    buffer.writeLongHex(ThreadLocalRandom.current().nextLong());
    return new String(traceId, UTF_8);
  }
}
