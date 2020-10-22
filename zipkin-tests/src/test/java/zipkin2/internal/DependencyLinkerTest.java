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
import java.util.logging.Level;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import zipkin2.DependencyLink;
import zipkin2.Endpoint;
import zipkin2.Span;
import zipkin2.Span.Kind;

import static java.util.Arrays.asList;
import static java.util.stream.Collectors.toList;
import static org.assertj.core.api.Assertions.assertThat;

public class DependencyLinkerTest {
  // in reverse order as reporting is more likely to occur this way
  static final List<Span> TRACE = asList(
    span("a", "b", "c", Kind.CLIENT, "app", "db", true),
    span("a", "a", "b", Kind.SERVER, "app", "web", false)
      .toBuilder().shared(true).build(),
    span("a", "a", "b", Kind.CLIENT, "web", "app", false),
    span("a", null, "a", Kind.SERVER, "web", null, false)
  );

  List<String> messages = new ArrayList<>();

  Logger logger = new Logger("", null) {
    {
      setLevel(Level.ALL);
    }

    @Override public void log(Level level, String msg) {
      assertThat(level).isEqualTo(Level.FINE);
      messages.add(msg);
    }
  };

  @Test void baseCase() {
    assertThat(new DependencyLinker().link()).isEmpty();
  }

  @Test void linksSpans() {
    assertThat(new DependencyLinker().putTrace(TRACE).link()).containsExactly(
      DependencyLink.newBuilder().parent("web").child("app").callCount(1L).build(),
      DependencyLink.newBuilder().parent("app").child("db").callCount(1L).errorCount(1L).build()
    );
  }

  /**
   * Trace id is not required to be a span id. For example, some instrumentation may create separate
   * trace ids to help with collisions, or to encode information about the origin. This test makes
   * sure we don't rely on the trace id = root span id convention.
   */
  @Test void traceIdIsOpaque() {
    List<DependencyLink> links = new DependencyLinker().putTrace(TRACE).link();

    List<Span> differentTraceId = TRACE.stream()
      .map(s -> s.toBuilder().traceId("123").build())
      .collect(toList());

    assertThat(new DependencyLinker().putTrace(differentTraceId).link())
      .containsExactlyElementsOf(links);
  }

  /**
   * Some don't propagate the server's parent ID which creates a race condition. Try to unwind it.
   *
   * <p>See https://github.com/openzipkin/zipkin/pull/1745
   */
  @Test void linksSpans_serverMissingParentId() {
    List<Span> trace = asList(
      span("a", null, "a", Kind.SERVER, "arn", null, false),
      span("a", "a", "b", Kind.CLIENT, "arn", "link", false),
      // below the parent ID is null as it wasn't propagated
      span("a", null, "b", Kind.SERVER, "link", "arn", false)
        .toBuilder().shared(true).build()
    );

    // trace is actually reported in reverse order
    Collections.reverse(trace);

    assertThat(new DependencyLinker().putTrace(trace).link()).containsExactly(
      DependencyLink.newBuilder().parent("arn").child("link").callCount(1L).build()
    );
  }

  /** In case of a late error, we should know which trace ID is being processed */
  @Test void logsTraceId() {
    new DependencyLinker(logger).putTrace(TRACE);

    assertThat(messages)
      .contains("building trace tree: traceId=000000000000000a");
  }

  /**
   * This test shows that if a parent ID is stored late (ex because it wasn't propagated), the span
   * can resolve once it is.
   */
  @Test void lateParentIdInSharedSpan() {
    List<Span> withLateParent = new ArrayList<>(TRACE);
    withLateParent.set(2, TRACE.get(2).toBuilder().parentId(null).build());

    assertThat(new DependencyLinker().putTrace(withLateParent).link()).containsExactly(
      DependencyLink.newBuilder().parent("web").child("app").callCount(1L).build(),
      DependencyLink.newBuilder().parent("app").child("db").callCount(1L).errorCount(1L).build()
    );
  }

  /**
   * This test shows that if a parent ID is stored late (ex because it wasn't propagated), the span
   * can resolve even if the client side is never sent
   */
  @Test void lostChildAndNoParentIdInSharedSpan() {
    List<Span> lostClientOrphan = new ArrayList<>(TRACE);
    lostClientOrphan.set(2, TRACE.get(2).toBuilder().parentId(null).build());
    lostClientOrphan.remove(1); // client span never sent

    assertThat(new DependencyLinker().putTrace(lostClientOrphan).link()).containsExactly(
      DependencyLink.newBuilder().parent("web").child("app").callCount(1L).build(),
      DependencyLink.newBuilder().parent("app").child("db").callCount(1L).errorCount(1L).build()
    );
  }

  @Test void messagingSpansDontLinkWithoutBroker_consumer() {
    List<Span> trace = asList(
      span("a", null, "a", Kind.PRODUCER, "producer", null, false),
      span("a", "a", "b", Kind.CONSUMER, "consumer", "kafka", false)
    );

    assertThat(new DependencyLinker().putTrace(trace).link()).containsOnly(
      DependencyLink.newBuilder().parent("kafka").child("consumer").callCount(1L).build()
    );
  }

  @Test void messagingSpansDontLinkWithoutBroker_producer() {
    List<Span> trace = asList(
      span("a", null, "a", Kind.PRODUCER, "producer", "kafka", false),
      span("a", "a", "b", Kind.CONSUMER, "consumer", null, false)
    );

    assertThat(new DependencyLinker().putTrace(trace).link()).containsOnly(
      DependencyLink.newBuilder().parent("producer").child("kafka").callCount(1L).build()
    );
  }

  @Test void messagingWithBroker_both_sides_same() {
    List<Span> trace = asList(
      span("a", null, "a", Kind.PRODUCER, "producer", "kafka", false),
      span("a", "a", "b", Kind.CONSUMER, "consumer", "kafka", false)
    );

    assertThat(new DependencyLinker().putTrace(trace).link()).containsOnly(
      DependencyLink.newBuilder().parent("producer").child("kafka").callCount(1L).build(),
      DependencyLink.newBuilder().parent("kafka").child("consumer").callCount(1L).build()
    );
  }

  @Test void messagingWithBroker_different() {
    List<Span> trace = asList(
      span("a", null, "a", Kind.PRODUCER, "producer", "kafka1", false),
      span("a", "a", "b", Kind.CONSUMER, "consumer", "kafka2", false)
    );

    assertThat(new DependencyLinker().putTrace(trace).link()).containsOnly(
      DependencyLink.newBuilder().parent("producer").child("kafka1").callCount(1L).build(),
      DependencyLink.newBuilder().parent("kafka2").child("consumer").callCount(1L).build()
    );
  }

  /** Shows we don't assume there's a direct link between producer and consumer. */
  @Test void messagingWithoutBroker_noLinks() {
    List<Span> trace = asList(
      span("a", null, "a", Kind.PRODUCER, "producer", null, false),
      span("a", "a", "b", Kind.CONSUMER, "consumer", null, false)
    );

    assertThat(new DependencyLinker().putTrace(trace).link())
      .isEmpty();
  }

  /** When a server is the child of a producer span, make a link as it is really an RPC */
  @Test void producerLinksToServer_childSpan() {
    List<Span> trace = asList(
      span("a", null, "a", Kind.PRODUCER, "producer", null, false),
      span("a", "a", "b", Kind.SERVER, "server", null, false)
    );

    assertThat(new DependencyLinker().putTrace(trace).link()).containsOnly(
      DependencyLink.newBuilder().parent("producer").child("server").callCount(1L).build()
    );
  }

  /**
   * Servers most often join a span vs create a child. Make sure this works when a producer is used
   * instead of a client.
   */
  @Test void producerLinksToServer_sameSpan() {
    List<Span> trace = asList(
      span("a", null, "a", Kind.PRODUCER, "producer", null, false),
      span("a", null, "a", Kind.SERVER, "server", null, false)
        .toBuilder().shared(true).build()
    );

    assertThat(new DependencyLinker().putTrace(trace).link()).containsOnly(
      DependencyLink.newBuilder().parent("producer").child("server").callCount(1L).build()
    );
  }

  /**
   * Client might be used for historical reasons instead of PRODUCER. Don't link as the server-side
   * is authoritative.
   */
  @Test void clientDoesntLinkToConsumer_child() {
    List<Span> trace = asList(
      span("a", null, "a", Kind.CLIENT, "client", null, false),
      span("a", "a", "b", Kind.CONSUMER, "consumer", null, false)
    );

    assertThat(new DependencyLinker().putTrace(trace).link())
      .isEmpty();
  }

  /**
   * A root span can be a client-originated trace or a server receipt which knows its peer. In these
   * cases, the peer is known and kind establishes the direction.
   */
  @Test void linksSpansDirectedByKind() {
    List<Span> validRootSpans = asList(
      span("a", null, "a", Kind.SERVER, "server", "client", false),
      span("a", null, "a", Kind.CLIENT, "client", "server", false)
        .toBuilder().shared(true).build()
    );

    for (Span span : validRootSpans) {
      assertThat(new DependencyLinker().putTrace(asList(span)).link()).containsOnly(
        DependencyLink.newBuilder().parent("client").child("server").callCount(1L).build()
      );
    }
  }

  @Test void callsAgainstTheSameLinkIncreasesCallCount_span() {
    List<Span> trace = asList(
      span("a", null, "a", Kind.SERVER, "client", null, false),
      span("a", "a", "b", Kind.CLIENT, null, "server", false),
      span("a", "a", "c", Kind.CLIENT, null, "server", false)
    );

    assertThat(new DependencyLinker().putTrace(trace).link()).containsOnly(
      DependencyLink.newBuilder().parent("client").child("server").callCount(2L).build()
    );
  }

  @Test void callsAgainstTheSameLinkIncreasesCallCount_trace() {
    List<Span> trace = asList(
      span("a", null, "a", Kind.SERVER, "client", null, false),
      span("a", "a", "b", Kind.CLIENT, null, "server", false)
    );

    assertThat(new DependencyLinker()
      .putTrace(trace)
      .putTrace(trace).link()).containsOnly(
      DependencyLink.newBuilder().parent("client").child("server").callCount(2L).build()
    );
  }

  /**
   * Spans don't always include both the client and server service. When you know the kind, you can
   * link these without duplicating call count.
   */
  @Test void singleHostSpansResultInASingleCallCount() {
    List<Span> trace = asList(
      span("a", null, "a", Kind.CLIENT, "client", null, false),
      span("a", "a", "b", Kind.SERVER, "server", null, false)
    );

    assertThat(new DependencyLinker().putTrace(trace).link()).containsOnly(
      DependencyLink.newBuilder().parent("client").child("server").callCount(1L).build()
    );
  }

  @Test void singleHostSpansResultInASingleErrorCount() {
    List<Span> trace = asList(
      span("a", null, "a", Kind.CLIENT, "client", null, true),
      span("a", "a", "b", Kind.SERVER, "server", null, true)
    );

    assertThat(new DependencyLinker().putTrace(trace).link()).containsOnly(
      DependencyLink.newBuilder()
        .parent("client")
        .child("server")
        .callCount(1L)
        .errorCount(1L)
        .build()
    );
  }

  @Test void singleHostSpansResultInASingleErrorCount_sameId() {
    List<Span> trace = asList(
      span("a", null, "a", Kind.CLIENT, "client", null, true),
      span("a", null, "a", Kind.SERVER, "server", null, true)
        .toBuilder().shared(true).build()
    );

    assertThat(new DependencyLinker().putTrace(trace).link()).containsOnly(
      DependencyLink.newBuilder()
        .parent("client")
        .child("server")
        .callCount(1L)
        .errorCount(1L)
        .build()
    );
  }

  @Test void singleHostSpansResultInASingleCallCount_defersNameToServer() {
    List<Span> trace = asList(
      span("a", null, "a", Kind.CLIENT, "client", "server", false),
      span("a", "a", "b", Kind.SERVER, "server", null, false)
    );

    assertThat(new DependencyLinker(logger).putTrace(trace).link()).containsOnly(
      DependencyLink.newBuilder().parent("client").child("server").callCount(1L).build()
    );
  }

  @Test void singleHostSpans_multipleChildren() {
    List<Span> trace = asList(
      span("a", null, "a", Kind.CLIENT, "client", null, false),
      span("a", "a", "b", Kind.SERVER, "server", "client", true),
      span("a", "a", "c", Kind.SERVER, "server", "client", false)
    );

    assertThat(new DependencyLinker().putTrace(trace).link()).containsOnly(
      DependencyLink.newBuilder()
        .parent("client")
        .child("server")
        .callCount(2L)
        .errorCount(1L)
        .build()
    );
  }

  @Test void singleHostSpans_multipleChildren_defersNameToServer() {
    List<Span> trace = asList(
      span("a", null, "a", Kind.CLIENT, "client", "server", false),
      span("a", "a", "b", Kind.SERVER, "server", null, false),
      span("a", "a", "c", Kind.SERVER, "server", null, false)
    );

    assertThat(new DependencyLinker(logger).putTrace(trace).link()).containsOnly(
      DependencyLink.newBuilder().parent("client").child("server").callCount(2L).build()
    );
  }

  /**
   * Spans are sometimes intermediated by an unknown type of span. Prefer the nearest server when
   * accounting for them.
   */
  @Test void intermediatedClientSpansMissingLocalServiceNameLinkToNearestServer() {
    List<Span> trace = asList(
      span("a", null, "a", Kind.SERVER, "client", null, false),
      span("a", "a", "b", null, null, null, false),
      // possibly a local fan-out span
      span("a", "b", "c", Kind.CLIENT, "server", null, false),
      span("a", "b", "d", Kind.CLIENT, "server", null, false)
    );

    assertThat(new DependencyLinker().putTrace(trace).link()).containsOnly(
      DependencyLink.newBuilder().parent("client").child("server").callCount(2L).build()
    );
  }

  @Test void errorsOnUninstrumentedLinks() {
    List<Span> trace = asList(
      span("a", null, "a", Kind.SERVER, "client", null, false),
      span("a", "a", "b", null, null, null, false),
      // there's no remote here, so we shouldn't see any error count
      span("a", "b", "c", Kind.CLIENT, "server", null, true),
      span("a", "b", "d", Kind.CLIENT, "server", null, true)
    );

    assertThat(new DependencyLinker().putTrace(trace).link()).containsOnly(
      DependencyLink.newBuilder().parent("client").child("server").callCount(2L).build()
    );
  }

  @Test void errorsOnInstrumentedLinks() {
    List<Span> trace = asList(
      span("a", null, "a", Kind.SERVER, "foo", null, false),
      span("a", "a", "b", null, null, null, false),
      span("a", "b", "c", Kind.CLIENT, "bar", "baz", true),
      span("a", "b", "d", Kind.CLIENT, "bar", "baz", false)
    );

    assertThat(new DependencyLinker().putTrace(trace).link()).containsOnly(
      DependencyLink.newBuilder().parent("foo").child("bar").callCount(2L).build(),
      DependencyLink.newBuilder().parent("bar").child("baz").callCount(2L).errorCount(1L).build()
    );
  }

  @Test void linkWithErrorIsLogged() {
    List<Span> trace = asList(
      span("a", "b", "c", Kind.CLIENT, "foo", "bar", true)
    );
    new DependencyLinker(logger).putTrace(trace).link();

    assertThat(messages).contains(
      "incrementing error link foo -> bar"
    );
  }

  /** Tag indicates a failed span, not an annotation */
  @Test void annotationNamedErrorDoesntIncrementErrorCount() {
    List<Span> trace = asList(
      span("a", "b", "c", Kind.CLIENT, "foo", "bar", false)
        .toBuilder().addAnnotation(1L, "error").build()
    );

    assertThat(new DependencyLinker().putTrace(trace).link()).containsOnly(
      DependencyLink.newBuilder().parent("foo").child("bar").callCount(1L).build()
    );
  }

  /** A loopback span is direction-agnostic, so can be linked properly regardless of kind. */
  @Test void linksLoopbackSpans() {
    List<Span> validRootSpans = asList(
      span("a", null, "a", Kind.SERVER, "service", "service", false),
      span("b", null, "b", Kind.CLIENT, "service", "service", false)
    );

    for (Span span : validRootSpans) {
      assertThat(new DependencyLinker().putTrace(asList(span)).link()).containsOnly(
        DependencyLink.newBuilder().parent("service").child("service").callCount(1L).build()
      );
    }
  }

  @Test void noSpanKindTreatedSameAsClient() {
    List<Span> trace = asList(
      span("a", null, "a", null, "some-client", "web", false),
      span("a", "a", "b", null, "web", "app", false),
      span("a", "b", "c", null, "app", "db", false)
    );

    assertThat(new DependencyLinker().putTrace(trace).link()).containsOnly(
      DependencyLink.newBuilder().parent("some-client").child("web").callCount(1L).build(),
      DependencyLink.newBuilder().parent("web").child("app").callCount(1L).build(),
      DependencyLink.newBuilder().parent("app").child("db").callCount(1L).build()
    );
  }

  @Test void noSpanKindWithError() {
    List<Span> trace = asList(
      span("a", null, "a", null, "some-client", "web", false),
      span("a", "a", "b", null, "web", "app", true),
      span("a", "b", "c", null, "app", "db", false)
    );

    assertThat(new DependencyLinker().putTrace(trace).link()).containsOnly(
      DependencyLink.newBuilder().parent("some-client").child("web").callCount(1L).build(),
      DependencyLink.newBuilder().parent("web").child("app").callCount(1L).errorCount(1L).build(),
      DependencyLink.newBuilder().parent("app").child("db").callCount(1L).build()
    );
  }

  /** A dependency link is between two services. We cannot link if we don't know both service names. */
  @Test void cannotLinkSingleSpanWithoutBothServiceNames() {
    List<Span> incompleteRootSpans = asList(
      span("a", null, "a", Kind.SERVER, null, null, false),
      span("a", null, "a", Kind.SERVER, "server", null, false),
      span("a", null, "a", Kind.SERVER, null, "client", false),
      span("a", null, "a", Kind.CLIENT, null, null, false),
      span("a", null, "a", Kind.CLIENT, "client", null, false),
      span("a", null, "a", Kind.CLIENT, null, "server", false)
    );

    for (Span span : incompleteRootSpans) {
      assertThat(new DependencyLinker(logger)
        .putTrace(asList(span)).link())
        .isEmpty();
    }
  }

  @Test void doesntLinkUnrelatedSpansWhenMissingRootSpan() {
    String missingParentId = "a";
    List<Span> trace = asList(
      span("a", missingParentId, "b", Kind.SERVER, "service1", null, false),
      span("a", missingParentId, "c", Kind.SERVER, "service2", null, false)
    );

    assertThat(new DependencyLinker(logger)
      .putTrace(trace).link())
      .isEmpty();
  }

  @Test void linksRelatedSpansWhenMissingRootSpan() {
    String missingParentId = "a";
    List<Span> trace = asList(
      span("a", missingParentId, "b", Kind.SERVER, "service1", null, false),
      span("a", "b", "c", Kind.SERVER, "service2", null, false)
    );

    assertThat(new DependencyLinker(logger).putTrace(trace).link()).containsOnly(
      DependencyLink.newBuilder().parent("service1").child("service2").callCount(1L).build()
    );
  }

  /** Client+Server spans that don't share IDs are treated as server spans missing their peer */
  @Test void linksSingleHostSpans() {
    List<Span> singleHostSpans = asList(
      span("a", null, "a", Kind.CLIENT, "web", null, false),
      span("a", "a", "b", Kind.SERVER, "app", null, false)
    );

    assertThat(new DependencyLinker().putTrace(singleHostSpans).link()).containsOnly(
      DependencyLink.newBuilder().parent("web").child("app").callCount(1L).build()
    );
  }

  @Test void linksSingleHostSpans_errorOnClient() {
    List<Span> trace = asList(
      span("a", null, "a", Kind.CLIENT, "web", null, true),
      span("a", "a", "b", Kind.SERVER, "app", null, false)
    );

    assertThat(new DependencyLinker().putTrace(trace).link()).containsOnly(
      DependencyLink.newBuilder().parent("web").child("app").callCount(1L).errorCount(1L).build()
    );
  }

  /** Creates a link when there's a span missing, in this case 2L which is an RPC from web to app */
  @Test void missingSpan() {
    List<Span> singleHostSpans = asList(
      span("a", null, "a", Kind.SERVER, "web", null, false),
      span("a", "a", "b", Kind.CLIENT, "app", null, false)
    );

    assertThat(new DependencyLinker(logger).putTrace(singleHostSpans).link())
      .containsOnly(DependencyLink.newBuilder().parent("web").child("app").callCount(1L).build());

    assertThat(messages).contains(
      "detected missing link to client span"
    );
  }

  @Test void merge() {
    List<DependencyLink> links = asList(
      DependencyLink.newBuilder().parent("foo").child("bar").callCount(2L).errorCount(1L).build(),
      DependencyLink.newBuilder().parent("foo").child("bar").callCount(2L).errorCount(2L).build(),
      DependencyLink.newBuilder().parent("foo").child("foo").callCount(1L).build()
    );

    assertThat(DependencyLinker.merge(links)).containsExactly(
      DependencyLink.newBuilder().parent("foo").child("bar").callCount(4L).errorCount(3L).build(),
      DependencyLink.newBuilder().parent("foo").child("foo").callCount(1L).build()
    );
  }

  @Test void merge_error() {
    List<DependencyLink> links = asList(
      DependencyLink.newBuilder().parent("client").child("server").callCount(2L).build(),
      DependencyLink.newBuilder().parent("client").child("server").callCount(2L).build(),
      DependencyLink.newBuilder().parent("client").child("client").callCount(1L).build()
    );

    assertThat(DependencyLinker.merge(links)).containsExactly(
      DependencyLink.newBuilder().parent("client").child("server").callCount(4L).build(),
      DependencyLink.newBuilder().parent("client").child("client").callCount(1L).build()
    );
  }

  static Span span(String traceId, @Nullable String parentId, String id, @Nullable Kind kind,
    @Nullable String local, @Nullable String remote, boolean isError) {
    Span.Builder result = Span.newBuilder().traceId(traceId).parentId(parentId).id(id).kind(kind);
    if (local != null) result.localEndpoint(Endpoint.newBuilder().serviceName(local).build());
    if (remote != null) result.remoteEndpoint(Endpoint.newBuilder().serviceName(remote).build());
    if (isError) result.putTag("error", "");
    return result.build();
  }
}
