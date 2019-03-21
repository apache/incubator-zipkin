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
package zipkin2.autoconfigure.collector.activemq;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.core.type.AnnotatedTypeMetadata;
import zipkin2.collector.CollectorMetrics;
import zipkin2.collector.CollectorSampler;
import zipkin2.collector.activemq.ActiveMQCollector;
import zipkin2.storage.StorageComponent;

import java.net.URISyntaxException;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;

/** Auto-configuration for {@link ActiveMQCollector}. */
@Configuration
@Conditional(ZipkinActiveMQCollectorAutoConfiguration.ActiveMQAddressesOrUriSet.class)
@EnableConfigurationProperties(ZipkinActiveMQCollectorProperties.class)
class ZipkinActiveMQCollectorAutoConfiguration {

  @Bean(initMethod = "start")
  ActiveMQCollector activemq(
    ZipkinActiveMQCollectorProperties properties,
      CollectorSampler sampler,
      CollectorMetrics metrics,
      StorageComponent storage)
      throws NoSuchAlgorithmException, KeyManagementException, URISyntaxException {
    return properties.toBuilder().sampler(sampler).metrics(metrics).storage(storage).build();
  }

  /**
   * This condition passes when {@link ZipkinActiveMQCollectorProperties#getAddresses()} is set to a non-empty value.
   *
   * <p>This is here because the yaml defaults this property to empty like this, and Spring Boot
   * doesn't have an option to treat empty properties as unset.
   *
   * <pre>{@code
   * addresses: ${RABBIT_ADDRESSES:}
   * uri: ${RABBIT_URI:}
   * }</pre>
   */
  static final class ActiveMQAddressesOrUriSet implements Condition {
    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata a) {
      return !isEmpty(context.getEnvironment().getProperty("zipkin.collector.activemq.addresses"));
    }

    private static boolean isEmpty(String s) {
      return s == null || s.isEmpty();
    }
  }
}
