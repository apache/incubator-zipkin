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
package zipkin2.server.internal.elasticsearch;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.ClientFactoryBuilder;
import com.linecorp.armeria.common.AggregatedHttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit4.server.ServerRule;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import zipkin2.elasticsearch.ElasticsearchStorage;

import static org.assertj.core.api.Assertions.assertThat;
import static zipkin2.server.internal.elasticsearch.TestResponses.YELLOW_RESPONSE;
import static zipkin2.server.internal.elasticsearch.ZipkinElasticsearchStorageConfiguration.QUALIFIER;

public class ITElasticsearchAuth {
  static final BlockingQueue<AggregatedHttpRequest> CAPTURED_REQUESTS = new LinkedBlockingQueue<>();

  @ClassRule public static ServerRule server = new ServerRule() {
    @Override protected void configure(ServerBuilder sb) throws Exception {
      sb.https(0);
      sb.tlsSelfSigned();

      sb.serviceUnder("/", (ctx, req) -> {
        // TODO: revisit in armeria 0.90
        CompletableFuture<HttpResponse> responseFuture = req.aggregate().thenApply(agg -> {
          CAPTURED_REQUESTS.add(agg);
          return HttpResponse.of(YELLOW_RESPONSE);
        });
        return HttpResponse.from(responseFuture);
      });
    }
  };

  @Configuration static class TlsSelfSignedConfiguration {
    @Bean @Qualifier(QUALIFIER) @Primary
    ClientFactory zipkinElasticsearchClientFactory() {
      return new ClientFactoryBuilder().sslContextCustomizer(
        ssl -> ssl.trustManager(InsecureTrustManagerFactory.INSTANCE)).build();
    }
  }

  AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
  ElasticsearchStorage storage;

  @Before public void init() {
    TestPropertyValues.of(
      "spring.config.name=zipkin-server",
      "zipkin.storage.type:elasticsearch",
      "zipkin.storage.elasticsearch.username:Aladdin",
      "zipkin.storage.elasticsearch.password:OpenSesame",
      "zipkin.storage.elasticsearch.hosts:https://127.0.0.1:" + server.httpsPort())
      .applyTo(context);
    Access.registerElasticsearch(context);
    context.register(TlsSelfSignedConfiguration.class);
    context.refresh();
    storage = context.getBean(ElasticsearchStorage.class);
  }

  @After public void close() {
    CAPTURED_REQUESTS.clear();
  }

  @Test public void healthcheck_usesAuthAndTls() throws Exception {
    assertThat(storage.check().ok()).isTrue();

    AggregatedHttpRequest next = CAPTURED_REQUESTS.take();
    // hard coded for sanity taken from https://en.wikipedia.org/wiki/Basic_access_authentication
    assertThat(next.headers().get("Authorization"))
      .isEqualTo("Basic QWxhZGRpbjpPcGVuU2VzYW1l");
  }
}
