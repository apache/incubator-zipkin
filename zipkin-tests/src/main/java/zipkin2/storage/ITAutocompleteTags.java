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
package zipkin2.storage;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import static java.util.Arrays.asList;
import static org.assertj.core.api.Assertions.assertThat;
import static zipkin2.TestObjects.spanBuilder;

/**
 * Base test for when {@link StorageComponent.Builder#autocompleteKeys(List)} has values.
 *
 * <p>Subtypes should create a connection to a real backend, even if that backend is in-process.
 */
public abstract class ITAutocompleteTags<T extends StorageComponent> extends ITStorage<T> {

  @Override protected final void configureStorageForTest(StorageComponent.Builder storage) {
    storage.autocompleteKeys(asList("http.host"));
  }

  @Test
  protected void ignores_when_key_not_in_autocompleteTags(TestInfo testInfo) throws Exception {
    String testSuffix = testSuffix(testInfo);
    accept(spanBuilder(testSuffix)
      .putTag("http.method", "GET")
      .build());

    assertThat(storage.autocompleteTags().getKeys().execute()).doesNotContain("http.method");

    assertThat(storage.autocompleteTags().getValues("http.method").execute()).isEmpty();
  }

  @Test protected void getTagsAndValues(TestInfo testInfo) throws Exception {
    String testSuffix = testSuffix(testInfo);
    for (int i = 0; i < 2; i++) {
      accept(spanBuilder(testSuffix)
        .putTag("http.method", "GET")
        .putTag("http.host", "host1")
        .build());
    }

    assertThat(storage.autocompleteTags().getKeys().execute())
      .containsOnlyOnce("http.host");

    assertThat(storage.autocompleteTags().getValues("http.host").execute())
      .containsOnlyOnce("host1");
  }
}
