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
package zipkin2.elasticsearch.internal;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.auto.value.AutoValue;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpRequestWriter;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.internal.shaded.guava.collect.ImmutableList;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.ByteBufOutputStream;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.handler.codec.http.QueryStringEncoder;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Supplier;
import zipkin2.elasticsearch.ElasticsearchStorage;
import zipkin2.elasticsearch.internal.client.HttpCall;
import zipkin2.elasticsearch.internal.client.HttpCall.BodyConverter;

import static zipkin2.Call.propagateIfFatal;
import static zipkin2.elasticsearch.internal.JsonSerializers.OBJECT_MAPPER;

// See https://www.elastic.co/guide/en/elasticsearch/reference/current/docs-bulk.html
// exposed to re-use for testing writes of dependency links
public final class BulkCallBuilder {
  // This mapper is invoked under the assumption that bulk requests return errors even when the http
  // status is success. The status codes expected to be returned were undocumented as of version 7.2
  // https://www.elastic.co/guide/en/elasticsearch/reference/current/docs-bulk.html
  static final BodyConverter<Void> CHECK_FOR_ERRORS = new BodyConverter<Void>() {
    @Override public Void convert(JsonParser parser, Supplier<String> contentString) {
      RuntimeException toThrow = null;
      try {
        JsonNode root = OBJECT_MAPPER.readTree(parser);
        // only throw when we know it is an error
        if (!root.at("/errors").booleanValue() && !root.at("/error").isObject()) return null;

        String message = root.findPath("reason").textValue();
        if (message == null) message = contentString.get();
        Number status = root.findPath("status").numberValue();
        if (status != null && status.intValue() == 429) {
          toThrow = new RejectedExecutionException(message);
        } else {
          toThrow = new RuntimeException(message);
        }

      } catch (RuntimeException | IOException possiblyParseException) { // All use of jackson throws
      }
      if (toThrow != null) throw toThrow;
      return null;
    }

    @Override public String toString() {
      return "CheckForErrors";
    }
  };

  final String tag;
  final boolean shouldAddType;
  final HttpCall.Factory http;
  final String pipeline;
  final boolean waitForRefresh;

  // Mutated for each call to index
  final List<IndexEntry<?>> entries = new ArrayList<>();

  public BulkCallBuilder(ElasticsearchStorage es, float esVersion, String tag) {
    this.tag = tag;
    shouldAddType = esVersion < 7.0f;
    http = Internal.instance.http(es);
    pipeline = es.pipeline();
    waitForRefresh = es.flushOnWrites();
  }

  static <T> IndexEntry<T> newIndexEntry(String index, String typeName, T input,
    BulkIndexWriter<T> writer) {
    return new AutoValue_BulkCallBuilder_IndexEntry<>(index, typeName, input, writer);
  }

  @AutoValue static abstract class IndexEntry<T> {
    abstract String index();

    abstract String typeName();

    abstract T input();

    abstract BulkIndexWriter<T> writer();
  }

  public <T> void index(String index, String typeName, T input, BulkIndexWriter<T> writer) {
    entries.add(newIndexEntry(index, typeName, input, writer));
  }

  /** Creates a bulk request when there is more than one object to store */
  public HttpCall<Void> build() {
    QueryStringEncoder urlBuilder = new QueryStringEncoder("/_bulk");
    if (pipeline != null) urlBuilder.addParam("pipeline", pipeline);
    if (waitForRefresh) urlBuilder.addParam("refresh", "wait_for");

    ByteBufAllocator alloc = RequestContext.mapCurrent(
      RequestContext::alloc, () -> PooledByteBufAllocator.DEFAULT);

    HttpCall.RequestSupplier request = new BulkRequestSupplier(
      entries,
      shouldAddType,
      RequestHeaders.of(
        HttpMethod.POST, urlBuilder.toString(),
        HttpHeaderNames.CONTENT_TYPE, MediaType.JSON_UTF_8),
      alloc);
    return http.newCall(request, CHECK_FOR_ERRORS, tag);
  }

  static class BulkRequestSupplier implements HttpCall.RequestSupplier {
    final List<IndexEntry<?>> entries;
    final boolean shouldAddType;
    final RequestHeaders headers;
    final ByteBufAllocator alloc;

    BulkRequestSupplier(List<IndexEntry<?>> entries, boolean shouldAddType,
      RequestHeaders headers, ByteBufAllocator alloc) {
      this.entries = ImmutableList.copyOf(entries);
      this.shouldAddType = shouldAddType;
      this.headers = headers;
      this.alloc = alloc;
    }

    @Override public RequestHeaders headers() {
      return headers;
    }

    @Override
    public HttpRequest get() {
      final HttpRequestWriter writer = HttpRequest.streaming(headers);
      writeEntry(writer, 0);
      return writer;
    }

    // There's a high chance that the response is received before the request is complete.
    // This can be a problem for BulkCallBuilder when it's sending streaming requests.
    // Hence, we use backpressure, so it's not buffering at all.
    private void writeEntry(HttpRequestWriter writer, int index) {
      if (index == entries.size()) {
        writer.close();
        return;
      }
      if (!writer.tryWrite(HttpData.wrap(serialize(alloc, entries.get(index), shouldAddType)))) {
        // Stream aborted, no need to serialize anymore.
        return;
      }
      writer.whenConsumed().thenRun(() -> writeEntry(writer, index + 1));
    }
  }

  static <T> ByteBuf serialize(ByteBufAllocator alloc, IndexEntry<T> entry,
    boolean shouldAddType) {
    // Fuzzily assume a general small span is 600 bytes to reduce resizing while building up the
    // JSON. Any extra bytes will be released back after serializing the document.
    ByteBuf document = alloc.heapBuffer(600);
    ByteBuf metadata = alloc.heapBuffer(200);
    try {
      String id = entry.writer().writeDocument(entry.input(), new ByteBufOutputStream(document));
      writeIndexMetadata(new ByteBufOutputStream(metadata), entry, id, shouldAddType);

      ByteBuf payload = alloc.ioBuffer(document.readableBytes() + metadata.readableBytes() + 2);
      try {
        payload.writeBytes(metadata).writeByte('\n').writeBytes(document).writeByte('\n');
      } catch (Throwable t) {
        payload.release();
        propagateIfFatal(t);
        Exceptions.throwUnsafely(t);
      }
      return payload;
    } finally {
      document.release();
      metadata.release();
    }
  }

  static <T> void writeIndexMetadata(ByteBufOutputStream sink, IndexEntry<T> entry, String id,
    boolean shouldAddType) {
    try (JsonGenerator writer = JsonSerializers.jsonGenerator(sink)) {
      writer.writeStartObject();
      writer.writeObjectFieldStart("index");
      writer.writeStringField("_index", entry.index());
      // the _type parameter is needed for Elasticsearch < 6.x
      if (shouldAddType) writer.writeStringField("_type", entry.typeName());
      writer.writeStringField("_id", id);
      writer.writeEndObject();
      writer.writeEndObject();
    } catch (IOException e) {
      throw new AssertionError(e); // No I/O writing to a Buffer.
    }
  }
}
