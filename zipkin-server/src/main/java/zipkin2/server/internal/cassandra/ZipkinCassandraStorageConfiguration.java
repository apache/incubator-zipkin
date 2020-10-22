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
package zipkin2.server.internal.cassandra;

import java.util.List;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import zipkin2.server.internal.ConditionalOnSelfTracing;
import zipkin2.storage.StorageComponent;
import zipkin2.storage.cassandra.v1.CassandraStorage;
import zipkin2.storage.cassandra.v1.SessionFactory;

/**
 * This storage accepts Cassandra logs in a specified category. Each log entry is expected to
 * contain a single span, which is TBinaryProtocol big-endian, then base64 encoded. Decoded spans
 * are stored asynchronously.
 */
@ConditionalOnClass(CassandraStorage.class)
@EnableConfigurationProperties(ZipkinCassandraStorageProperties.class)
@ConditionalOnProperty(name = "zipkin.storage.type", havingValue = "cassandra")
@ConditionalOnMissingBean(StorageComponent.class)
@Import(ZipkinCassandraStorageConfiguration.TracingSessionFactoryEnhancer.class)
public class ZipkinCassandraStorageConfiguration {

  @Bean SessionFactory sessionFactory() {
    return new SessionFactory.Default();
  }

  @Bean
  @ConditionalOnMissingBean
  StorageComponent storage(
      ZipkinCassandraStorageProperties properties,
      SessionFactory sessionFactory,
      @Value("${zipkin.storage.strict-trace-id:true}") boolean strictTraceId,
      @Value("${zipkin.storage.search-enabled:true}") boolean searchEnabled,
      @Value("${zipkin.storage.autocomplete-keys:}") List<String> autocompleteKeys,
      @Value("${zipkin.storage.autocomplete-ttl:3600000}") int autocompleteTtl,
      @Value("${zipkin.storage.autocomplete-cardinality:20000}") int autocompleteCardinality) {
   return properties.toBuilder()
      .strictTraceId(strictTraceId)
      .searchEnabled(searchEnabled)
      .autocompleteKeys(autocompleteKeys)
      .autocompleteTtl(autocompleteTtl)
      .autocompleteCardinality(autocompleteCardinality)
      .sessionFactory(sessionFactory).build();
  }

  @ConditionalOnSelfTracing
  static class TracingSessionFactoryEnhancer implements BeanPostProcessor, BeanFactoryAware {
    /**
     * Need this to resolve cyclic instantiation issue with spring when instantiating with tracing.
     *
     * <p>Ref: <a href="https://stackoverflow.com/a/19688634">Tracking down cause of Spring's "not
     * eligible for auto-proxying"</a></p>
     */
    BeanFactory beanFactory;

    @Override public Object postProcessBeforeInitialization(Object bean, String beanName) {
      return bean;
    }

    @Override public Object postProcessAfterInitialization(Object bean, String beanName) {
      //if (bean instanceof SessionFactory && beanFactory.containsBean("tracing")) {
      //  SessionFactory delegate = (SessionFactory) bean;
      //  Tracing tracing = beanFactory.getBean(Tracing.class);
      //  return (SessionFactory) storage -> TracingSession.create(tracing, delegate.create(storage));
      //}
      return bean;
    }

    @Override public void setBeanFactory(BeanFactory beanFactory) throws BeansException {
      this.beanFactory = beanFactory;
    }
  }
}
