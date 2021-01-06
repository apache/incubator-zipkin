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
package zipkin2.server.internal.prometheus;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.Route;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.SimpleDecoratingHttpService;
import com.linecorp.armeria.spring.ArmeriaServerConfigurator;
import io.micrometer.core.instrument.Clock;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.binder.jvm.ClassLoaderMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmGcMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmThreadMetrics;
import io.micrometer.core.instrument.binder.system.ProcessorMetrics;
import io.micrometer.prometheus.PrometheusConfig;
import io.micrometer.prometheus.PrometheusMeterRegistry;
import io.netty.util.AttributeKey;
import io.prometheus.client.CollectorRegistry;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.util.StringUtils;

@Configuration(proxyBeanMethods = false)
public class ZipkinPrometheusMetricsConfiguration {
  // from io.micrometer.spring.web.servlet.WebMvcTags
  private static final Tag URI_NOT_FOUND = Tag.of("uri", "NOT_FOUND");
  private static final Tag URI_REDIRECTION = Tag.of("uri", "REDIRECTION");
  private static final Tag URI_TRACE_V2 = Tag.of("uri", "/api/v2/trace/{traceId}");
  // single-page app requests are forwarded to index: ZipkinUiConfiguration.forwardUiEndpoints
  private static final Tag URI_CROSSROADS = Tag.of("uri", "/zipkin/index.html");

  final String metricName;

  // https://docs.spring.io/spring-boot/docs/current/reference/htmlsingle/#production-ready-metrics-spring-mvc
  ZipkinPrometheusMetricsConfiguration(
    @Value("${management.metrics.web.server.requests-metric-name:http.server.requests}")
      String metricName
  ) {
    this.metricName = metricName;
  }

  @Bean @ConditionalOnMissingBean public Clock clock() {
    return Clock.SYSTEM;
  }

  @Bean @ConditionalOnMissingBean public PrometheusConfig config() {
    return PrometheusConfig.DEFAULT;
  }

  @Bean @ConditionalOnMissingBean public CollectorRegistry registry() {
    return new CollectorRegistry(true);
  }

  @Bean @ConditionalOnMissingBean public PrometheusMeterRegistry prometheusMeterRegistry(
    PrometheusConfig config, CollectorRegistry registry, Clock clock) {
    PrometheusMeterRegistry meterRegistry = new PrometheusMeterRegistry(config, registry, clock);
    new JvmMemoryMetrics().bindTo(meterRegistry);
    new JvmGcMetrics().bindTo(meterRegistry);
    new JvmThreadMetrics().bindTo(meterRegistry);
    new ClassLoaderMetrics().bindTo(meterRegistry);
    new ProcessorMetrics().bindTo(meterRegistry);
    return meterRegistry;
  }

  // https://docs.spring.io/spring-boot/docs/current/reference/htmlsingle/#production-ready-metrics-spring-mvc
  @Bean ArmeriaServerConfigurator httpRequestDurationConfigurator(MeterRegistry registry) {
    return serverBuilder -> serverBuilder.routeDecorator()
      .pathPrefix("/zipkin/api")
      .pathPrefix("/api")
      .build(s -> new MetricCollectingService(s, registry, metricName));
  }

  // We need to make sure not-found requests are still handled by a service to be decorated for
  // adding metrics. We add a lower precedence path mapping so anything not mapped by another
  // service is handled by this.
  @Bean
  @Order(1)
  ArmeriaServerConfigurator notFoundMetricCollector() {
    // Use glob instead of catch-all to avoid adding it to the trie router.
    return sb -> sb.service(Route.builder().glob("/**").build(),
      (ctx, req) -> HttpResponse.of(HttpStatus.NOT_FOUND));
  }

  static final class MetricCollectingService extends SimpleDecoratingHttpService {
    final MeterRegistry registry;
    final String metricName;

    MetricCollectingService(HttpService delegate, MeterRegistry registry, String metricName) {
      super(delegate);
      this.registry = registry;
      this.metricName = metricName;
    }

    @Override
    public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
      setup(ctx, registry, metricName);
      return unwrap().serve(ctx, req);
    }
  }

  // A variable to make sure setup method is not called twice.
  private static final AttributeKey<Boolean> PROMETHEUS_METRICS_SET =
    AttributeKey.valueOf(Boolean.class, "PROMETHEUS_METRICS_SET");

  @SuppressWarnings("FutureReturnValueIgnored") // no known action to take following .thenAccept
  public static void setup(RequestContext ctx, MeterRegistry registry, String metricName) {
    if (ctx.hasAttr(PROMETHEUS_METRICS_SET)) {
      return;
    }
    ctx.setAttr(PROMETHEUS_METRICS_SET, true);
    ctx.log().whenComplete().thenAccept(log -> getTimeBuilder(log, metricName).register(registry)
      .record(log.totalDurationNanos(), TimeUnit.NANOSECONDS));
  }

  private static Timer.Builder getTimeBuilder(RequestLog requestLog, String metricName) {
    return Timer.builder(metricName)
      .tags(getTags(requestLog))
      .description("Response time histogram")
      .publishPercentileHistogram();
  }

  private static Iterable<Tag> getTags(RequestLog requestLog) {
    return Arrays.asList(Tag.of("method", requestLog.requestHeaders().method().toString())
      , uri(requestLog)
      , Tag.of("status", Integer.toString(requestLog.responseHeaders().status().code()))
    );
  }

  /** Ensure metrics cardinality doesn't blow up on variables */
  private static Tag uri(RequestLog requestLog) {
    int status = requestLog.responseHeaders().status().code();
    if (status > 299 && status < 400) return URI_REDIRECTION;
    if (status == 404) return URI_NOT_FOUND;

    String uri = getPathInfo(requestLog);
    if (uri.startsWith("/zipkin")) {
      if (uri.equals("/zipkin/") || uri.equals("/zipkin")
        || uri.startsWith("/zipkin/traces/")
        || uri.equals("/zipkin/dependency")
        || uri.equals("/zipkin/traceViewer")) {
        return URI_CROSSROADS; // single-page app route
      }

      // un-map UI's api route
      if (uri.startsWith("/zipkin/api")) {
        uri = uri.replaceFirst("/zipkin", "");
      }
    }
    // handle templated routes instead of exploding on trace ID cardinality
    if (uri.startsWith("/api/v2/trace/")) return URI_TRACE_V2;
    return Tag.of("uri", uri);
  }

  // from io.micrometer.spring.web.servlet.WebMvcTags
  static String getPathInfo(RequestLog requestLog) {
    String uri = requestLog.context().path();
    if (!StringUtils.hasText(uri)) return "/";
    return uri.replaceAll("//+", "/")
      .replaceAll("/$", "");
  }
}
