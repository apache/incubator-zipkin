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

import com.linecorp.armeria.spring.ArmeriaAutoConfiguration;
import org.springframework.context.annotation.Import;
import zipkin2.server.internal.activemq.ZipkinActiveMQCollectorConfiguration;
import zipkin2.server.internal.brave.ZipkinSelfTracingConfiguration;
import zipkin2.server.internal.cassandra3.ZipkinCassandra3StorageConfiguration;
import zipkin2.server.internal.elasticsearch.ZipkinElasticsearchStorageConfiguration;
import zipkin2.server.internal.eureka.ZipkinEurekaConfiguration;
import zipkin2.server.internal.health.ZipkinHealthController;
import zipkin2.server.internal.kafka.ZipkinKafkaCollectorConfiguration;
import zipkin2.server.internal.mysql.ZipkinMySQLStorageConfiguration;
import zipkin2.server.internal.prometheus.ZipkinMetricsController;
import zipkin2.server.internal.prometheus.ZipkinPrometheusMetricsConfiguration;
import zipkin2.server.internal.rabbitmq.ZipkinRabbitMQCollectorConfiguration;
import zipkin2.server.internal.scribe.ZipkinScribeCollectorConfiguration;
import zipkin2.server.internal.ui.ZipkinUiConfiguration;

@Import({
  ArmeriaAutoConfiguration.class,
  ZipkinConfiguration.class,
  ZipkinHttpConfiguration.class,
  ZipkinUiConfiguration.class,
  ZipkinEurekaConfiguration.class,
  ZipkinCassandra3StorageConfiguration.class,
  ZipkinElasticsearchStorageConfiguration.class,
  ZipkinMySQLStorageConfiguration.class,
  ZipkinScribeCollectorConfiguration.class,
  ZipkinSelfTracingConfiguration.class,
  ZipkinQueryApiV2.class,
  ZipkinHttpCollector.class,
  ZipkinGrpcCollector.class,
  ZipkinActiveMQCollectorConfiguration.class,
  ZipkinKafkaCollectorConfiguration.class,
  ZipkinRabbitMQCollectorConfiguration.class,
  ZipkinMetricsController.class,
  ZipkinHealthController.class,
  ZipkinPrometheusMetricsConfiguration.class
})
public class InternalZipkinConfiguration {
}
