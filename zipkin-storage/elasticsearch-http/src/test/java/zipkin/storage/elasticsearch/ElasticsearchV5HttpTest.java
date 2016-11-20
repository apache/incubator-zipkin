/**
 * Copyright 2015-2016 The OpenZipkin Authors
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
package zipkin.storage.elasticsearch;

import org.junit.ClassRule;
import org.junit.experimental.runners.Enclosed;
import org.junit.runner.RunWith;
import zipkin.storage.SpanStoreTest;

import java.io.IOException;

@RunWith(Enclosed.class)
public class ElasticsearchV5HttpTest {

  @ClassRule
  public static LazyElasticsearchHttpStorage storage =
      new LazyElasticsearchHttpStorage("elasticsearch:5.0.1");

  public static class DependenciesTest extends ElasticsearchDependenciesTest {

    @Override protected ElasticsearchStorage storage() {
      return storage.get();
    }
  }

  public static class SpanConsumerTest extends ElasticsearchSpanConsumerTest {

    @Override protected ElasticsearchStorage storage() {
      return storage.get();
    }
  }

  public static class ElasticsearchSpanStoreTest extends SpanStoreTest {

    @Override protected ElasticsearchStorage storage() {
      return storage.get();
    }

    @Override public void clear() throws IOException {
      storage().clear();
    }
  }

  public static class StrictTraceIdFalseTest extends ElasticsearchStrictTraceIdFalseTest {

    @Override protected ElasticsearchStorage.Builder storageBuilder() {
      return ElasticsearchV5HttpTest.storage.computeStorageBuilder();
    }
  }
}
