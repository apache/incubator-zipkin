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
package zipkin2.elasticsearch.integration;

import java.io.IOException;
import java.util.List;
import org.junit.AfterClass;
import org.junit.AssumptionViolatedException;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.experimental.runners.Enclosed;
import org.junit.rules.TestName;
import org.junit.runner.RunWith;
import zipkin2.CheckResult;
import zipkin2.Span;
import zipkin2.elasticsearch.ElasticsearchStorage;
import zipkin2.elasticsearch.InternalForTests;
import zipkin2.storage.StorageComponent;

import static zipkin2.elasticsearch.integration.ElasticsearchStorageRule.index;

@RunWith(Enclosed.class)
public class ITElasticsearchStorageV7 {

  static ElasticsearchStorageRule classRule() {
    return new ElasticsearchStorageRule("openzipkin/zipkin-elasticsearch7:2.15.0",
      "test_elasticsearch3");
  }

  public static class ITSpanStore extends zipkin2.storage.ITSpanStore {
    @ClassRule public static ElasticsearchStorageRule backend = classRule();

    static ElasticsearchStorage storage;

    @BeforeClass public static void connect() {
      storage = backend.computeStorageBuilder().index("zipkin-test").build();
      CheckResult check = storage.check();
      if (!check.ok()) {
        throw new AssumptionViolatedException(check.error().getMessage(), check.error());
      }
    }

    @Override protected StorageComponent storage() {
      return storage;
    }

    @Before @Override public void clear() throws IOException {
      storage.clear();
    }

    @AfterClass public static void close() {
      storage.close();
    }
  }

  public static class ITSearchEnabledFalse extends zipkin2.storage.ITSearchEnabledFalse {
    @ClassRule public static ElasticsearchStorageRule backend = classRule();

    static ElasticsearchStorage storage;

    @BeforeClass public static void connect() {
      storage = backend.computeStorageBuilder().index("zipkin-test")
        .searchEnabled(false).build();
      CheckResult check = storage.check();
      if (!check.ok()) {
        throw new AssumptionViolatedException(check.error().getMessage(), check.error());
      }
    }

    @Override protected StorageComponent storage() {
      return storage;
    }

    @Before @Override public void clear() throws IOException {
      storage.clear();
    }

    @AfterClass public static void close() {
      storage.close();
    }
  }

  public static class ITServiceAndSpanNames extends zipkin2.storage.ITServiceAndSpanNames {
    @ClassRule public static ElasticsearchStorageRule backend = classRule();

    static ElasticsearchStorage storage;

    @BeforeClass public static void connect() {
      storage = backend.computeStorageBuilder().index("zipkin-test").build();
      CheckResult check = storage.check();
      if (!check.ok()) {
        throw new AssumptionViolatedException(check.error().getMessage(), check.error());
      }
    }

    @Override protected StorageComponent storage() {
      return storage;
    }

    @Before @Override public void clear() throws IOException {
      storage.clear();
    }

    @AfterClass public static void close() {
      storage.close();
    }
  }

  public static class ITAutocompleteTags extends zipkin2.storage.ITAutocompleteTags {
    @ClassRule public static ElasticsearchStorageRule backend = classRule();
    @Rule public TestName testName = new TestName();

    @Override protected StorageComponent.Builder storageBuilder() {
      return backend.computeStorageBuilder().index(index(testName));
    }

    @Override public void clear() throws IOException {
      ((ElasticsearchStorage) storage).clear();
    }
  }

  public static class ITStrictTraceIdFalse extends zipkin2.storage.ITStrictTraceIdFalse {
    @ClassRule public static ElasticsearchStorageRule backend = classRule();

    static ElasticsearchStorage storage;

    @BeforeClass public static void connect() {
      storage = backend.computeStorageBuilder().index("zipkin-test").strictTraceId(false).build();
      CheckResult check = storage.check();
      if (!check.ok()) {
        throw new AssumptionViolatedException(check.error().getMessage(), check.error());
      }
    }

    @Override protected StorageComponent storage() {
      return storage;
    }

    @Before @Override public void clear() throws IOException {
      storage.clear();
    }

    @AfterClass public static void close() {
      storage.close();
    }
  }

  public static class ITDependencies extends zipkin2.storage.ITDependencies {
    @ClassRule public static ElasticsearchStorageRule backend = classRule();

    static ElasticsearchStorage storage;

    @BeforeClass public static void connect() {
      storage = backend.computeStorageBuilder().index("zipkin-test").build();
      CheckResult check = storage.check();
      if (!check.ok()) {
        throw new AssumptionViolatedException(check.error().getMessage(), check.error());
      }
    }

    @Override protected StorageComponent storage() {
      return storage;
    }

    /**
     * The current implementation does not include dependency aggregation. It includes retrieval of
     * pre-aggregated links, usually made via zipkin-dependencies
     */
    @Override protected void processDependencies(List<Span> spans) throws Exception {
      aggregateLinks(spans).forEach(
        (midnight, links) -> InternalForTests.writeDependencyLinks(storage, links, midnight));
    }

    @Before @Override public void clear() throws IOException {
      storage.clear();
    }

    @AfterClass public static void close() {
      storage.close();
    }
  }
}
