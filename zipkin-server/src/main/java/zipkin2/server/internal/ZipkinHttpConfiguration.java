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
package zipkin2.server.internal;

import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.cors.CorsService;
import com.linecorp.armeria.server.cors.CorsServiceBuilder;
import com.linecorp.armeria.server.file.HttpFile;
import com.linecorp.armeria.server.metric.PrometheusExpositionService;
import com.linecorp.armeria.spring.ArmeriaServerConfigurator;
import io.micrometer.core.instrument.MeterRegistry;
import io.prometheus.client.CollectorRegistry;
import java.time.Duration;
import java.util.Optional;
import java.util.function.Function;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import zipkin2.server.internal.health.ZipkinHealthController;
import zipkin2.server.internal.prometheus.ZipkinMetricsController;

@Configuration(proxyBeanMethods = false)
public class ZipkinHttpConfiguration {
  public static final MediaType MEDIA_TYPE_ACTUATOR =
    MediaType.parse("application/vnd.spring-boot.actuator.v2+json;charset=UTF-8");

  @Bean ArmeriaServerConfigurator serverConfigurator(
    Optional<ZipkinQueryApiV2> httpQuery,
    Optional<ZipkinHttpCollector> httpCollector,
    Optional<ZipkinHealthController> healthController,
    Optional<ZipkinMetricsController> metricsController,
    Optional<MeterRegistry> meterRegistry,
    Optional<CollectorRegistry> collectorRegistry,
    @Value("${zipkin.query.timeout:11s}") Duration queryTimeout) {
    return sb -> {
      httpQuery.ifPresent(h -> {
        Function<HttpService, HttpService>
          timeoutDecorator = service -> (ctx, req) -> {
          ctx.setRequestTimeout(queryTimeout);
          return service.serve(ctx, req);
        };
        sb.annotatedService(httpQuery.get(), timeoutDecorator);
        sb.annotatedService("/zipkin", httpQuery.get(), timeoutDecorator); // For UI.
      });
      httpCollector.ifPresent(sb::annotatedService);
      healthController.ifPresent(sb::annotatedService);
      metricsController.ifPresent(sb::annotatedService);
      collectorRegistry.ifPresent(registry -> {
        PrometheusExpositionService prometheusService = new PrometheusExpositionService(registry);
        sb.service("/actuator/prometheus", prometheusService);
        sb.service("/prometheus", prometheusService);
      });

      // Directly implement info endpoint, but use different content type for the /actuator path
      sb.service("/actuator/info", infoService(MEDIA_TYPE_ACTUATOR));
      sb.service("/info", infoService(MediaType.JSON_UTF_8));

      // It's common for backend requests to have timeouts of the magic number 10s, so we go ahead
      // and default to a slightly longer timeout on the server to be able to handle these with
      // better error messages where possible.
      sb.requestTimeout(Duration.ofSeconds(11));

      // Block TRACE requests because https://github.com/openzipkin/zipkin/issues/2286
      sb.routeDecorator().trace("prefix:/")
        .build((delegate, ctx, req) -> {
          if (req.method() == HttpMethod.TRACE) { // TODO: we shouldn't need to double-check!
            return HttpResponse.of(HttpStatus.METHOD_NOT_ALLOWED);
          }
          return delegate.serve(ctx, req);
        });
    };
  }

  /** Configures the server at the last because of the specified {@link Order} annotation. */
  @Order @Bean ArmeriaServerConfigurator corsConfigurator(
    @Value("${zipkin.query.allowed-origins:*}") String allowedOrigins) {
    CorsServiceBuilder corsBuilder = CorsService.builder(allowedOrigins.split(","))
      // NOTE: The property says query, and the UI does not use POST, but we allow POST?
      //
      // The reason is that our former CORS implementation accidentally allowed POST. People doing
      // browser-based tracing relied on this, so we can't remove it by default. In the future, we
      // could split the collector's CORS policy into a different property, still allowing POST
      // with content-type by default.
      .allowRequestMethods(HttpMethod.GET, HttpMethod.POST)
      .allowRequestHeaders(HttpHeaderNames.CONTENT_TYPE,
        // Use literals to avoid a runtime dependency on armeria-grpc types
        HttpHeaderNames.of("X-GRPC-WEB"))
      .exposeHeaders("grpc-status", "grpc-message", "armeria.grpc.ThrowableProto-bin");
    return builder -> builder.decorator(corsBuilder::build);
  }

  HttpService infoService(MediaType mediaType) {
    return HttpFile.builder(getClass().getClassLoader(), "info.json")
      .contentType(mediaType)
      .build()
      .asService();
  }
}
