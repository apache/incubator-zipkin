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

import brave.kafka.clients.KafkaTracing;
import java.util.Optional;
import java.util.Properties;
import java.util.function.Function;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.type.AnnotatedTypeMetadata;
import zipkin2.collector.CollectorMetrics;
import zipkin2.collector.CollectorSampler;
import zipkin2.collector.kafka.KafkaCollector;
import zipkin2.server.internal.ConditionalOnSelfTracing;
import zipkin2.storage.StorageComponent;

/**
 * This collector consumes a topic, decodes spans from thrift messages and stores them subject to
 * sampling policy.
 */
@ConditionalOnClass(KafkaCollector.class)
@Conditional(ZipkinKafkaCollectorConfiguration.KafkaBootstrapServersSet.class)
@EnableConfigurationProperties(ZipkinKafkaCollectorProperties.class)
public class ZipkinKafkaCollectorConfiguration { // makes simple type name unique for /actuator/conditions
  static final String QUALIFIER = "zipkinKafka";

  @Bean(initMethod = "start") KafkaCollector kafka(
    ZipkinKafkaCollectorProperties properties,
    CollectorSampler sampler,
    CollectorMetrics metrics,
    StorageComponent storage,
    java.util.function.Consumer<KafkaCollector.Builder> kafkaTracing) {
    final KafkaCollector.Builder builder = properties.toBuilder()
      .sampler(sampler)
      .metrics(metrics)
      .storage(storage);
    kafkaTracing.accept(builder);
    return builder.build();
  }

  @Bean @Qualifier(QUALIFIER) @ConditionalOnSelfTracing
  java.util.function.Consumer<KafkaCollector.Builder> consumerSupplier(
    Optional<KafkaTracing> maybeKafkaTracing
  ) {
    return builder ->
      builder.consumerSupplier(
        maybeKafkaTracing
          .<Function<Properties, Consumer<byte[], byte[]>>>
            map(kafkaTracing -> props -> kafkaTracing.consumer(new KafkaConsumer<>(props)))
          .orElseGet(() -> KafkaConsumer::new));
  }

  /**
   * This condition passes when {@link ZipkinKafkaCollectorProperties#getBootstrapServers()} is set
   * to non-empty.
   *
   * <p>This is here because the yaml defaults this property to empty like this, and spring-boot
   * doesn't have an option to treat empty properties as unset.
   *
   * <pre>{@code
   * bootstrap-servers: ${KAFKA_BOOTSTRAP_SERVERS:}
   * }</pre>
   */
  static final class KafkaBootstrapServersSet implements Condition {
    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata a) {
      return !isEmpty(
        context.getEnvironment().getProperty("zipkin.collector.kafka.bootstrap-servers")) &&
        notFalse(context.getEnvironment().getProperty("zipkin.collector.kafka.enabled"));
    }

    private static boolean isEmpty(String s) {
      return s == null || s.isEmpty();
    }

    private static boolean notFalse(String s) {
      return s == null || !s.equals("false");
    }
  }
}
