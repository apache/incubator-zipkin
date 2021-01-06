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

import com.linecorp.armeria.server.eureka.EurekaUpdatingListener;
import com.linecorp.armeria.spring.ArmeriaServerConfigurator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Auto-configuration for {@link EurekaUpdatingListener}.
 *
 * <p>See https://armeria.dev/docs/server-service-registration#eureka-based-service-registration-with-eurekaupdatinglistener
 */
@ConditionalOnClass(EurekaUpdatingListener.class)
@Conditional(ZipkinEurekaConfiguration.EurekaUrlSet.class)
@Configuration(proxyBeanMethods = false)
public class ZipkinEurekaConfiguration {
  @Bean ArmeriaServerConfigurator eurekaListener(
    @Value("${server.port:9411}") int port,
    @Value("${zipkin.discovery.eureka.url}") String eurekaUrl,
    @Value("${zipkin.discovery.eureka.appName:zipkin}") String appName,
    @Value("${zipkin.discovery.eureka.instanceId:zipkin-server}") String instanceId) {
    return sb ->
      sb.serverListener(
        EurekaUpdatingListener.builder(eurekaUrl)
          .appName(appName)
          // why can't this just take from the server?
          .port(port)
          .instanceId(instanceId)
          // This will be wrong /health isn't allowed and our endpoint returns content, so can't
          // implement HealthCheckService ex hostname and port are both likely wrong.
          .healthCheckUrl("http://localhost:" + port + "/health")
          .build());
  }

  /**
   * This condition passes when "zipkin.discovery.eureka.url" is set to non-empty.
   *
   * <p>This is here because the yaml defaults this property to empty like this, and spring-boot
   * doesn't have an option to treat empty properties as unset.
   *
   * <pre>{@code
   * url: ${EUREKA_URL:}
   * }</pre>
   */
  static final class EurekaUrlSet implements Condition {
    @Override public boolean matches(ConditionContext context, AnnotatedTypeMetadata a) {
      return !isEmpty(
        context.getEnvironment().getProperty("zipkin.discovery.eureka.url")) &&
        notFalse(context.getEnvironment().getProperty("zipkin.discovery.eureka.enabled"));
    }

    private static boolean isEmpty(String s) {
      return s == null || s.isEmpty();
    }

    private static boolean notFalse(String s) {
      return s == null || !s.equals("false");
    }
  }
}
