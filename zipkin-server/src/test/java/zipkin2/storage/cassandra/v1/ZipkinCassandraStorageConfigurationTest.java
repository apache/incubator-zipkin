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
package zipkin2.storage.cassandra.v1;

import org.junit.After;
import org.junit.Test;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import zipkin2.server.internal.cassandra.Access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class ZipkinCassandraStorageConfigurationTest {

  AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();

  @After public void close() {
    context.close();
  }

  @Test public void doesntProvidesStorageComponent_whenStorageTypeNotCassandra() {
    TestPropertyValues.of("zipkin.storage.type:elasticsearch").applyTo(context);
    Access.registerCassandra(context);
    context.refresh();

    assertThatThrownBy(() -> context.getBean(CassandraStorage.class))
      .isInstanceOf(NoSuchBeanDefinitionException.class);
  }

  @Test public void providesStorageComponent_whenStorageTypeCassandra() {
    TestPropertyValues.of("zipkin.storage.type:cassandra").applyTo(context);
    Access.registerCassandra(context);
    context.refresh();

    assertThat(context.getBean(CassandraStorage.class)).isNotNull();
  }

  @Test public void canOverridesProperty_contactPoints() {
    TestPropertyValues.of(
      "zipkin.storage.type:cassandra",
      "zipkin.storage.cassandra.contact-points:host1,host2" // note snake-case supported
    ).applyTo(context);
    Access.registerCassandra(context);
    context.refresh();

    assertThat(context.getBean(CassandraStorage.class).contactPoints).isEqualTo("host1,host2");
  }

  @Test public void strictTraceId_defaultsToTrue() {
    TestPropertyValues.of("zipkin.storage.type:cassandra").applyTo(context);
    Access.registerCassandra(context);
    context.refresh();
    assertThat(context.getBean(CassandraStorage.class).strictTraceId).isTrue();
  }

  @Test public void strictTraceId_canSetToFalse() {
    context = new AnnotationConfigApplicationContext();
    TestPropertyValues.of(
      "zipkin.storage.type:cassandra",
      "zipkin.storage.strict-trace-id:false")
      .applyTo(context);
    Access.registerCassandra(context);
    context.refresh();

    assertThat(context.getBean(CassandraStorage.class).strictTraceId).isFalse();
  }

  @Test public void autocompleteKeys_list() {
    context = new AnnotationConfigApplicationContext();
    TestPropertyValues.of(
      "zipkin.storage.type:cassandra",
      "zipkin.storage.autocomplete-keys:environment")
      .applyTo(context);
    Access.registerCassandra(context);
    context.refresh();

    assertThat(context.getBean(CassandraStorage.class).autocompleteKeys)
      .containsOnly("environment");
  }

  @Test public void autocompleteTtl() {
    context = new AnnotationConfigApplicationContext();
    TestPropertyValues.of(
      "zipkin.storage.type:cassandra",
      "zipkin.storage.autocomplete-ttl:60000")
      .applyTo(context);
    Access.registerCassandra(context);
    context.refresh();

    assertThat(context.getBean(CassandraStorage.class).autocompleteTtl)
      .isEqualTo(60000);
  }

  @Test public void autocompleteCardinality() {
    context = new AnnotationConfigApplicationContext();
    TestPropertyValues.of(
      "zipkin.storage.type:cassandra",
      "zipkin.storage.autocomplete-cardinality:5000")
      .applyTo(context);
    Access.registerCassandra(context);
    context.refresh();

    assertThat(context.getBean(CassandraStorage.class).autocompleteCardinality)
      .isEqualTo(5000);
  }
}
