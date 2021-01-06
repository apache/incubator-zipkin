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
package zipkin2.server.internal.kafka;

import java.util.function.Consumer;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import zipkin2.collector.kafka.KafkaCollector;
import zipkin2.server.internal.InMemoryConfiguration;
import zipkin2.server.internal.brave.ZipkinSelfTracingConfiguration;

import static org.assertj.core.api.Assertions.assertThat;

public class ZipkinKafkaCollectorConfigurationTest {

  @Rule public ExpectedException thrown = ExpectedException.none();

  AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();

  @After public void close() {
    context.close();
  }

  @Test public void doesNotProvideCollectorComponent_whenBootstrapServersUnset() {
    context.register(
      PropertyPlaceholderAutoConfiguration.class,
      ZipkinKafkaCollectorConfiguration.class,
      InMemoryConfiguration.class);
    context.refresh();

    thrown.expect(NoSuchBeanDefinitionException.class);
    context.getBean(KafkaCollector.class);
  }

  @Test public void providesCollectorComponent_whenBootstrapServersEmptyString() {
    TestPropertyValues.of("zipkin.collector.kafka.bootstrap-servers:").applyTo(context);
    context.register(
      PropertyPlaceholderAutoConfiguration.class,
      ZipkinKafkaCollectorConfiguration.class,
      InMemoryConfiguration.class);
    context.refresh();

    thrown.expect(NoSuchBeanDefinitionException.class);
    context.getBean(KafkaCollector.class);
  }

  @Test public void providesCollectorComponent_whenBootstrapServersSet() {
    TestPropertyValues.of("zipkin.collector.kafka.bootstrap-servers:localhost:9092")
      .applyTo(context);
    context.register(
      PropertyPlaceholderAutoConfiguration.class,
      ZipkinKafkaCollectorConfiguration.class,
      InMemoryConfiguration.class,
      ZipkinSelfTracingConfiguration.class,
      KafkaTracingCustomization.class);
    context.refresh();

    assertThat(context.getBean(KafkaCollector.class)).isNotNull();
  }

  @Test public void doesNotProvidesCollectorComponent_whenBootstrapServersSetAndDisabled() {
    TestPropertyValues.of("zipkin.collector.kafka.bootstrap-servers:localhost:9092")
      .applyTo(context);
    TestPropertyValues.of("zipkin.collector.kafka.enabled:false").applyTo(context);
    context.register(
      PropertyPlaceholderAutoConfiguration.class,
      ZipkinKafkaCollectorConfiguration.class,
      InMemoryConfiguration.class);
    context.refresh();

    thrown.expect(NoSuchBeanDefinitionException.class);
    context.getBean(KafkaCollector.class);
  }

  @Configuration
  static class KafkaTracingCustomization {

    @Bean @Qualifier("zipkinKafka") public Consumer<KafkaCollector.Builder> one() {
      return builderConsumer;
    }

    Consumer<KafkaCollector.Builder> builderConsumer =
      builder -> builder.consumerSupplier(KafkaConsumer::new);
  }
}
