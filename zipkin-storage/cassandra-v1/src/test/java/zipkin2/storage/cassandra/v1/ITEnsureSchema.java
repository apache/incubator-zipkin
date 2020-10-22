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

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.metadata.schema.KeyspaceMetadata;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import zipkin2.CheckResult;
import zipkin2.Span;
import zipkin2.TestObjects;
import zipkin2.storage.ITStorage;
import zipkin2.storage.QueryRequest;
import zipkin2.storage.StorageComponent;

import static java.util.Arrays.asList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static zipkin2.TestObjects.BACKEND;
import static zipkin2.TestObjects.appendSuffix;
import static zipkin2.TestObjects.newTrace;

/** This test is very slow as installing the schema can take 10s per method. */
abstract class ITEnsureSchema extends ITStorage<CassandraStorage> {
  @Override protected abstract CassandraStorage.Builder newStorageBuilder(
    TestInfo testInfo);

  @Override protected void configureStorageForTest(StorageComponent.Builder storage) {
    ((CassandraStorage.Builder) storage).ensureSchema(false)
      .autocompleteKeys(asList("environment"));
  }

  @Override protected boolean initializeStoragePerTest() {
    return true; // We need a different keyspace per test
  }

  @Override protected void checkStorage() {
    // don't check as it requires the keyspace which these tests install
  }

  abstract CqlSession session();

  @Test public void installsKeyspaceWhenMissing() {
    Schema.ensureExists(storage.keyspace, session());

    KeyspaceMetadata metadata = session().getMetadata().getKeyspace(storage.keyspace).get();
    assertThat(metadata).isNotNull();
    assertThat(Schema.hasUpgrade1_defaultTtl(metadata)).isTrue();
  }

  @Test public void installsTablesWhenMissing() {
    session().execute("CREATE KEYSPACE " + storage.keyspace
      + " WITH replication = {'class': 'SimpleStrategy', 'replication_factor': '1'};");

    Schema.ensureExists(storage.keyspace, session());

    KeyspaceMetadata metadata = session().getMetadata().getKeyspace(storage.keyspace).get();
    assertThat(metadata).isNotNull();
    assertThat(Schema.hasUpgrade1_defaultTtl(metadata)).isTrue();
    assertThat(metadata.getTable("autocomplete_tags")).isPresent();
  }

  @Test public void upgradesOldSchema() {
    Schema.applyCqlFile(storage.keyspace, session(), "/cassandra-schema-cql3-original.txt");

    Schema.ensureExists(storage.keyspace, session());

    KeyspaceMetadata metadata = session().getMetadata().getKeyspace(storage.keyspace).get();
    assertThat(metadata).isNotNull();
    assertThat(Schema.hasUpgrade1_defaultTtl(metadata)).isTrue();
    assertThat(Schema.hasUpgrade2_autocompleteTags(metadata)).isTrue();
  }

  /** This tests we don't accidentally rely on new indexes such as autocomplete tags */
  @Test public void worksWithOldSchem(TestInfo testInfo) throws Exception {
    String testSuffix = testSuffix(testInfo);
    Schema.applyCqlFile(storage.keyspace, session(), "/cassandra-schema-cql3-original.txt");

    // Ensure the storage component is functional before proceeding
    CheckResult check = storage.check();
    if (!check.ok()) {
      throw new AssertionError("Could not connect to storage: "
        + check.error().getMessage(), check.error());
    }

    List<Span> trace = newTrace(testSuffix);

    accept(trace);

    assertGetTraceReturns(trace.get(0).traceId(), trace);

    assertThat(storage.autocompleteTags().getValues("environment").execute())
      .isEmpty(); // instead of an exception
    String serviceName = TestObjects.TRACE.get(0).localServiceName();
    assertThat(storage.serviceAndSpanNames().getRemoteServiceNames(serviceName).execute())
      .isEmpty(); // instead of an exception

    QueryRequest request = requestBuilder()
      .serviceName(serviceName)
      .remoteServiceName(appendSuffix(BACKEND.serviceName(), testSuffix)).build();

    // Make sure there's an error if a query will return incorrectly vs returning invalid results
    assertThatThrownBy(() -> storage.spanStore().getTraces(request))
      .isInstanceOf(IllegalArgumentException.class)
      .hasMessage("remoteService=" + trace.get(1).remoteServiceName() +
        " unsupported due to missing table service_remote_service_name_index");
  }
}
