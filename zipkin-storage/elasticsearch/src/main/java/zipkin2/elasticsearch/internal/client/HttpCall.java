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
package zipkin2.elasticsearch.internal.client;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.linecorp.armeria.client.Clients;
import com.linecorp.armeria.client.UnprocessedRequestException;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpRequest;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.HttpStatusClass;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.common.util.SafeCloseable;
import io.netty.util.concurrent.EventExecutor;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Supplier;
import zipkin2.Call;
import zipkin2.Callback;

import static zipkin2.elasticsearch.internal.JsonSerializers.JSON_FACTORY;
import static zipkin2.elasticsearch.internal.JsonSerializers.OBJECT_MAPPER;

public final class HttpCall<V> extends Call.Base<V> {

  public interface BodyConverter<V> {
    /**
     * Prefer using the {@code parser} for request-scoped conversions. Typically, {@code
     * contentString} is only for an unexpected failure.
     */
    V convert(JsonParser parser, Supplier<String> contentString) throws IOException;
  }

  /**
   * A request stream which can have {@link HttpData} of the request body written to it.
   */
  public interface RequestStream {
    /**
     * Writes the {@link HttpData} to the stream. Returns {@code false} if the stream has been
     * aborted (e.g., the request timed out while writing), or {@code true} otherwise.
     */
    boolean tryWrite(HttpData obj);
  }

  /**
   * A supplier of {@linkplain HttpHeaders headers} and {@linkplain HttpData body} of a request to
   * Elasticsearch.
   */
  public interface RequestSupplier extends Supplier<HttpRequest> {
    /**
     * Returns the {@linkplain HttpHeaders headers} for this request.
     */
    RequestHeaders headers();
  }

  static class AggregatedRequestSupplier implements RequestSupplier {

    final AggregatedHttpRequest request;

    AggregatedRequestSupplier(AggregatedHttpRequest request) {
      try (HttpData content = request.content()) {
        if (!content.isPooled()) {
          this.request = request;
        } else {
          // Unfortunately it's not possible to use pooled objects in requests and support clone()
          // after sending the request.
          this.request = AggregatedHttpRequest.of(
            request.headers(), HttpData.wrap(content.array()), request.trailers());
        }
      }
    }

    @Override public RequestHeaders headers() {
      return request.headers();
    }

    @Override
    public HttpRequest get() {
      return request.toHttpRequest();
    }
  }

  public static class Factory {
    final WebClient httpClient;

    public Factory(WebClient httpClient) {
      this.httpClient = httpClient;
    }

    public <V> HttpCall<V> newCall(
      AggregatedHttpRequest request, BodyConverter<V> bodyConverter, String name) {
      return new HttpCall<>(
        httpClient, new AggregatedRequestSupplier(request), bodyConverter, name);
    }

    public <V> HttpCall<V> newCall(
      RequestSupplier request, BodyConverter<V> bodyConverter, String name) {
      return new HttpCall<>(httpClient, request, bodyConverter, name);
    }
  }

  // Visible for benchmarks
  public final RequestSupplier request;
  final BodyConverter<V> bodyConverter;
  final String name;

  final WebClient httpClient;

  volatile CompletableFuture<AggregatedHttpResponse> responseFuture;

  HttpCall(WebClient httpClient, RequestSupplier request, BodyConverter<V> bodyConverter,
    String name) {
    this.httpClient = httpClient;
    this.name = name;
    this.request = request;
    this.bodyConverter = bodyConverter;
  }

  @Override protected V doExecute() throws IOException {
    // TODO: testme
    for (EventExecutor eventLoop : httpClient.options().factory().eventLoopGroup()) {
      if (eventLoop.inEventLoop()) {
        throw new RuntimeException("Attempting to make a blocking request from an event loop. "
          + "Either use doEnqueue() or run this in a separate thread.");
      }
    }
    final AggregatedHttpResponse response;
    try {
      response = sendRequest().join();
    } catch (CompletionException e) {
      propagateIfFatal(e);
      Exceptions.throwUnsafely(e.getCause());
      return null;  // Unreachable
    }
    return parseResponse(response, bodyConverter);
  }

  @SuppressWarnings("FutureReturnValueIgnored")
  // TODO: errorprone wants us to check this future before returning, but what would be a sensible
  // check? Say it is somehow canceled, would we take action? Would callback.onError() be redundant?
  @Override protected void doEnqueue(Callback<V> callback) {
    sendRequest().handle((response, t) -> {
      if (t != null) {
        callback.onError(t);
      } else {
        try {
          V value = parseResponse(response, bodyConverter);
          callback.onSuccess(value);
        } catch (Throwable t1) {
          propagateIfFatal(t1);
          callback.onError(t1);
        }
      }
      return null;
    });
  }

  @Override protected void doCancel() {
    CompletableFuture<AggregatedHttpResponse> responseFuture = this.responseFuture;
    if (responseFuture != null) {
      responseFuture.cancel(false);
    }
  }

  @Override public HttpCall<V> clone() {
    return new HttpCall<>(httpClient, request, bodyConverter, name);
  }

  @Override public String toString() {
    return "HttpCall(" + request + ")";
  }

  CompletableFuture<AggregatedHttpResponse> sendRequest() {
    final HttpResponse response;
    try (SafeCloseable ignored =
           Clients.withContextCustomizer(ctx -> ctx.logBuilder().name(name))) {
      response = httpClient.execute(request.get());
    }
    CompletableFuture<AggregatedHttpResponse> responseFuture =
      RequestContext.mapCurrent(
        ctx -> response.aggregateWithPooledObjects(ctx.eventLoop(), ctx.alloc()),
        // This should never be used in practice since the module runs in an Armeria server.
        response::aggregate);
    responseFuture = responseFuture.exceptionally(t -> {
      if (t instanceof UnprocessedRequestException) {
        Throwable cause = t.getCause();
        // Go ahead and reduce the output in logs since this is usually a configuration or
        // infrastructure issue and the Armeria stack trace won't help debugging that.
        Exceptions.clearTrace(cause);

        String message = cause.getMessage();
        if (message == null) message = cause.getClass().getSimpleName();
        throw new RejectedExecutionException(message, cause);
      } else {
        Exceptions.throwUnsafely(t);
      }
      return null;
    });
    this.responseFuture = responseFuture;
    return responseFuture;
  }

  V parseResponse(AggregatedHttpResponse response, BodyConverter<V> bodyConverter)
    throws IOException {
    // Handle the case where there is no content, as that means we have no resources to release.
    HttpStatus status = response.status();
    if (response.content().isEmpty()) {
      if (status.codeClass().equals(HttpStatusClass.SUCCESS)) {
        return null;
      } else if (status.code() == 404) {
        throw new FileNotFoundException(request.headers().path());
      } else {
        throw new RuntimeException(
          "response for " + request.headers().path() + " failed: " + response.status());
      }
    }

    // If this is a client or server error, we look for a json message.
    if ((status.codeClass().equals(HttpStatusClass.CLIENT_ERROR)
      || status.codeClass().equals(HttpStatusClass.SERVER_ERROR))) {
      bodyConverter = (parser, contentString) -> {
        String message = null;
        try {
          JsonNode root = OBJECT_MAPPER.readTree(parser);
          message = root.findPath("reason").textValue();
          if (message == null) message = root.at("/Message").textValue();
        } catch (RuntimeException | IOException possiblyParseException) {
          // EmptyCatch ignored
        }
        throw new RuntimeException(message != null ? message
          : "response for " + request.headers().path() + " failed: " + contentString.get());
      };
    }

    try (HttpData content = response.content();
         InputStream stream = content.toInputStream();
         JsonParser parser = JSON_FACTORY.createParser(stream)) {

      if (status.code() == 404) throw new FileNotFoundException(request.headers().path());

      return bodyConverter.convert(parser, content::toStringUtf8);
    }
  }
}
