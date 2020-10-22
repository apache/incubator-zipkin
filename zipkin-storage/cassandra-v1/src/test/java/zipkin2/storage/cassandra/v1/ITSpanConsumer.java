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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import zipkin2.Span;
import zipkin2.storage.ITStorage;
import zipkin2.storage.StorageComponent;

import static java.util.Arrays.asList;
import static org.assertj.core.api.Assertions.assertThat;
import static zipkin2.TestObjects.newClientSpan;
import static zipkin2.storage.cassandra.v1.CassandraStorageExtension.rowCount;

abstract class ITSpanConsumer extends ITStorage<CassandraStorage> {
  @Override protected void configureStorageForTest(StorageComponent.Builder storage) {
    storage.autocompleteKeys(asList("environment"));
  }

  /**
   * Core/Boundary annotations like "sr" aren't queryable, and don't add value to users. Address
   * annotations, like "sa", don't have string values, so are similarly not queryable. Skipping
   * indexing of such annotations dramatically reduces the load on cassandra and size of indexes.
   */
  @Test public void doesntIndexCoreOrNonStringAnnotations(TestInfo testInfo) throws Exception {
    String testSuffix = testSuffix(testInfo);
    Span clientSpan = newClientSpan(testSuffix).toBuilder().putTag("environment", "test").build();

    accept(clientSpan);

    assertThat(storage.session().execute("SELECT blobastext(annotation) from annotations_index")
      .all())
      .extracting(r -> r.getString(0))
      .containsExactlyInAnyOrder(
        clientSpan.localServiceName() + ":http.method:GET",
        clientSpan.localServiceName() + ":http.path",
        clientSpan.localServiceName() + ":http.method",
        clientSpan.localServiceName() + ":environment:test",
        clientSpan.localServiceName() + ":http.path:/foo",
        clientSpan.localServiceName() + ":environment");
  }

  @Test public void addsAutocompleteTag(TestInfo testInfo) throws Exception {
    String testSuffix = testSuffix(testInfo);
    Span[] trace = new Span[2];
    trace[0] = newClientSpan(testSuffix);

    trace[1] = Span.newBuilder()
      .traceId(trace[0].traceId())
      .parentId(trace[0].id())
      .id(1)
      .name("1")
      .putTag("environment", "dev")
      .putTag("a", "b")
      .localEndpoint(trace[0].localEndpoint())
      .timestamp(trace[0].timestampAsLong() + 1000L) // child span timestamps happen 1ms later
      .addAnnotation(trace[0].timestampAsLong() + 1500L, "bar")
      .build();
    accept(trace);

    assertThat(rowCount(storage, Tables.AUTOCOMPLETE_TAGS))
      .isGreaterThanOrEqualTo(1L);

    assertThat(getTagValue(storage, "environment")).isEqualTo("dev");
  }

  static String getTagValue(CassandraStorage storage, String key) {
    return storage
      .session()
      .execute("SELECT value from " + Tables.AUTOCOMPLETE_TAGS + " WHERE key='" + key + "'")
      .one()
      .getString(0);
  }
}
