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

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import zipkin2.DependencyLink;
import zipkin2.Endpoint;
import zipkin2.Span;
import zipkin2.Span.Kind;

import static org.assertj.core.api.Assertions.assertThat;
import static zipkin2.TestObjects.BACKEND;
import static zipkin2.TestObjects.DAY;
import static zipkin2.TestObjects.DB;
import static zipkin2.TestObjects.FRONTEND;
import static zipkin2.TestObjects.TODAY;
import static zipkin2.TestObjects.endTs;
import static zipkin2.TestObjects.newTraceId;

/**
 * Base heavy tests for {@link SpanStore} implementations that support dependency aggregation.
 * Subtypes should create a connection to a real backend, even if that backend is in-process.
 *
 * <p>As these tests create a lot of data, implementations may wish to isolate them from other
 * integration tests such as {@link ITDependencies}
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class ITDependenciesHeavy<T extends StorageComponent> extends ITStorage<T> {
  @Override protected boolean initializeStoragePerTest() {
    return true;
  }

  @Override protected void configureStorageForTest(StorageComponent.Builder storage) {
    // Defaults are fine.
  }

  /**
   * Override if dependency processing is a separate job: it should complete before returning from
   * this method.
   */
  protected void processDependencies(List<Span> spans) throws Exception {
    storage.spanConsumer().accept(spans).execute();
    blockWhileInFlight();
  }

  /** Ensure there's no query limit problem around links */
  @Test protected void manyLinks() throws Exception {
    int count = 1000; // Larger than 10, which is the default ES search limit that tripped this
    List<Span> spans = new ArrayList<>(count);
    for (int i = 1; i <= count; i++) {
      String traceId = newTraceId();

      Endpoint web = FRONTEND.toBuilder().serviceName("web-" + i).build();
      Endpoint app = BACKEND.toBuilder().serviceName("app-" + i).build();
      Endpoint db = DB.toBuilder().serviceName("db-" + i).build();

      spans.add(Span.newBuilder().traceId(traceId).id("10").name("get")
        .timestamp((TODAY + 50L) * 1000L).duration(250L * 1000L)
        .kind(Kind.CLIENT)
        .localEndpoint(web)
        .build()
      );
      spans.add(Span.newBuilder().traceId(traceId).id("10").name("get").shared(true)
        .timestamp((TODAY + 100) * 1000L).duration(150 * 1000L)
        .kind(Kind.SERVER)
        .localEndpoint(app)
        .build()
      );
      spans.add(Span.newBuilder().traceId(traceId).parentId("10").id("11").name("get")
        .timestamp((TODAY + 150L) * 1000L).duration(50L * 1000L)
        .kind(Kind.CLIENT)
        .localEndpoint(app)
        .remoteEndpoint(db)
        .build()
      );
    }

    processDependencies(spans);

    List<DependencyLink> links = store().getDependencies(endTs(spans), DAY).execute();
    assertThat(links).hasSize(count * 2); // web-? -> app-?, app-? -> db-?
    assertThat(links).extracting(DependencyLink::callCount)
      .allSatisfy(callCount -> assertThat(callCount).isEqualTo(1));
  }
}
