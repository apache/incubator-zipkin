/*
 * Copyright 2015-2019 The OpenZipkin Authors
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
package zipkin2.server.internal.health;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.annotation.Get;
import java.io.IOException;
import java.io.StringWriter;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import zipkin2.Component;

import static com.linecorp.armeria.common.HttpHeaderNames.CONTENT_LENGTH;
import static java.nio.charset.StandardCharsets.UTF_8;
import static zipkin2.server.internal.ZipkinServerConfiguration.MEDIA_TYPE_ACTUATOR;
import static zipkin2.server.internal.health.ComponentHealth.STATUS_DOWN;
import static zipkin2.server.internal.health.ComponentHealth.STATUS_UP;

public class ZipkinHealthController {
  static final JsonFactory JSON_FACTORY = new JsonFactory();

  final List<Component> components;

  ZipkinHealthController(List<Component> components) {
    this.components = components;
  }

  @Get("/actuator/health")
  public CompletableFuture<HttpResponse> getActuatorHealth(ServiceRequestContext ctx) {
    return health(ctx, MEDIA_TYPE_ACTUATOR);
  }

  @Get("/health")
  public CompletableFuture<HttpResponse> getHealth(ServiceRequestContext ctx) {
    return health(ctx, MediaType.JSON_UTF_8);
  }

  CompletableFuture<HttpResponse> health(ServiceRequestContext ctx, MediaType mediaType) {
    CompletableFuture<HttpResponse> responseFuture = new CompletableFuture<>();
    ctx.setRequestTimeoutHandler(() -> {
      try {
        String healthJson = writeJsonError("Timed out computing health status. "
          + "This often means your storage backend is unreachable.");
        responseFuture.complete(newHealthResponse(STATUS_DOWN, mediaType, healthJson));
      } catch (Throwable e) {
        // Shouldn't happen since we serialize to an array.
        responseFuture.completeExceptionally(e);
      }
    });

    ctx.blockingTaskExecutor().execute(() -> {
      try {
        responseFuture.complete(newHealthResponse(componentHealths(components), mediaType));
      } catch (Throwable e) {
        responseFuture.completeExceptionally(e);
      }
    });
    return responseFuture;
  }

  static List<ComponentHealth> componentHealths(List<Component> components) {
    return components.stream()
      .parallel().map(ComponentHealth::ofComponent)
      .collect(Collectors.toList());
  }

  static HttpResponse newHealthResponse(List<ComponentHealth> healths, MediaType mediaType)
    throws IOException {

    String overallStatus = STATUS_UP;
    for (ComponentHealth health : healths) {
      if (health.status.equals(STATUS_DOWN)) overallStatus = STATUS_DOWN;
    }

    String healthJson = writeJson(overallStatus, healths);
    return newHealthResponse(overallStatus, mediaType, healthJson);
  }

  static HttpResponse newHealthResponse(String overallStatus, MediaType mediaType,
    String healthJson) {
    byte[] body = healthJson.getBytes(UTF_8);
    int code = overallStatus.equals(STATUS_UP) ? 200 : 503;
    ResponseHeaders headers = ResponseHeaders.builder(code)
      .contentType(mediaType)
      .setInt(CONTENT_LENGTH, body.length).build();
    return HttpResponse.of(AggregatedHttpResponse.of(headers, HttpData.wrap(body)));
  }

  static String writeJsonError(String error) throws IOException {
    StringWriter writer = new StringWriter();
    JsonGenerator generator = JSON_FACTORY.createGenerator(writer);
    generator.writeStartObject();
    generator.writeStringField("status", STATUS_DOWN);
    generator.writeObjectFieldStart("zipkin");
    generator.writeStringField("status", STATUS_DOWN);
    generator.writeObjectFieldStart("details");
    generator.writeStringField("error", error);
    generator.writeEndObject(); // .zipkin.details
    generator.writeEndObject(); // .zipkin
    generator.writeEndObject(); // .
    generator.flush();
    return writer.toString();
  }

  static String writeJson(String overallStatus, List<ComponentHealth> healths) throws IOException {
    StringWriter writer = new StringWriter();
    JsonGenerator generator = JSON_FACTORY.createGenerator(writer);
    generator.useDefaultPrettyPrinter();
    generator.writeStartObject();
    generator.writeStringField("status", overallStatus);
    generator.writeObjectFieldStart("zipkin");
    generator.writeStringField("status", overallStatus);
    generator.writeObjectFieldStart("details");

    for (ComponentHealth health : healths) {
      generator.writeObjectFieldStart(health.name);
      generator.writeStringField("status", health.status);

      if (health.status.equals(STATUS_DOWN)) {
        generator.writeObjectFieldStart("details");
        generator.writeStringField("error", health.error);
        generator.writeEndObject(); // .zipkin.details.healthName.details
      }

      generator.writeEndObject(); // .zipkin.details.healthName
    }

    generator.writeEndObject(); // .zipkin.details
    generator.writeEndObject(); // .zipkin
    generator.writeEndObject(); // .
    generator.flush();
    return writer.toString();
  }
}
