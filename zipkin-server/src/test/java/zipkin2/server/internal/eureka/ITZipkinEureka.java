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
package zipkin2.server.internal.eureka;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.spring.ArmeriaAutoConfiguration;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.ConversionService;
import zipkin2.server.internal.ZipkinConfiguration;
import zipkin2.server.internal.ZipkinHttpConfiguration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

public class ITZipkinEureka {

  static final AtomicReference<HttpData> registerContentCaptor = new AtomicReference<>();

  static final CompletableFuture<RequestHeaders> heartBeatHeadersCaptor =
    new CompletableFuture<>();
  static final CompletableFuture<RequestHeaders> deregisterHeadersCaptor =
    new CompletableFuture<>();

  @RegisterExtension static final ServerExtension eurekaServer = new ServerExtension() {
    @Override protected void configure(ServerBuilder sb) {
      sb.service("/apps/zipkin", (ctx, req) -> {
        if (req.method() != HttpMethod.POST) {
          registerContentCaptor.set(null);
          return HttpResponse.of(HttpStatus.METHOD_NOT_ALLOWED);
        }
        final CompletableFuture<HttpResponse> future = new CompletableFuture<>();
        req.aggregate().handle((aggregatedRes, cause) -> {
          registerContentCaptor.set(aggregatedRes.content());
          future.complete(HttpResponse.of(HttpStatus.NO_CONTENT));
          return null;
        });
        return HttpResponse.from(future);
      });
      final AtomicInteger heartBeatRequestCounter = new AtomicInteger();
      sb.service("/apps/zipkin/{instanceId}", (ctx, req) -> {
        req.aggregate();
        if (req.method() == HttpMethod.PUT) {
          final int count = heartBeatRequestCounter.getAndIncrement();
          if (count == 0) {
            // This is for the test that EurekaUpdatingListener automatically retries when
            // RetryingClient is not used.
            return HttpResponse.of(HttpStatus.INTERNAL_SERVER_ERROR);
          }
          heartBeatHeadersCaptor.complete(req.headers());
        } else if (req.method() == HttpMethod.DELETE) {
          deregisterHeadersCaptor.complete(req.headers());
        }
        return HttpResponse.of(HttpStatus.OK);
      });
    }
  };

  AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
  Server server;

  @BeforeEach void init() {
    TestPropertyValues.of(
      "server.port=9413",
      "zipkin.query.enabled=false",
      "zipkin.ui.enabled=false",
      "zipkin.discovery.eureka.url=" + eurekaServer.httpUri()
    ).applyTo(context);

    context.register(
      ConversionServiceConfig.class,
      PropertyPlaceholderAutoConfiguration.class,
      ArmeriaAutoConfiguration.class,
      ZipkinConfiguration.class,
      ZipkinHttpConfiguration.class,
      ZipkinEurekaConfiguration.class
    );
    context.refresh();

    server = context.getBean(Server.class);
  }

  @Test void registersInEureka() {
    await().until(() -> registerContentCaptor.get() != null);
    assertThat(registerContentCaptor.get().toStringAscii())
      .isEqualTo("sdd");
  }

  @AfterEach public void close() {
    context.close();
  }

  @Configuration static class ConversionServiceConfig {
    @Bean ConversionService conversionService() {
      return ApplicationConversionService.getSharedInstance();
    }
  }
}
