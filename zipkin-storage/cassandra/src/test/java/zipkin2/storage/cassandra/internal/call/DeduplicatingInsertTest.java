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
package zipkin2.storage.cassandra.internal.call;

import com.datastax.oss.driver.api.core.cql.AsyncResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import zipkin2.Call;
import zipkin2.Callback;
import zipkin2.internal.DelayLimiter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.failBecauseExceptionWasNotThrown;
import static org.mockito.Mockito.mock;

public class DeduplicatingInsertTest {
  List<String> values = new ArrayList<>();
  AtomicReference<String> failValue = new AtomicReference<>();

  DeduplicatingInsert.Factory<String> callFactory = new Factory();

  @Test public void dedupesSameCalls() throws Exception {
    List<Call<Void>> calls = new ArrayList<>();
    callFactory.maybeAdd("foo", calls);
    callFactory.maybeAdd("bar", calls);
    callFactory.maybeAdd("foo", calls);
    callFactory.maybeAdd("bar", calls);
    callFactory.maybeAdd("bar", calls);
    assertThat(calls).hasSize(2);

    for (Call<Void> call : calls) {
      call.execute();
    }
    assertThat(values).containsExactly("foo", "bar");
  }

  Callback<Void> assertFailOnError = new Callback<Void>() {
    @Override public void onSuccess(Void value) {
    }

    @Override public void onError(Throwable t) {
      throw (AssertionError) t;
    }
  };

  @Test public void enqueuesInOrder() {
    List<Call<Void>> calls = new ArrayList<>();
    callFactory.maybeAdd("foo", calls);
    callFactory.maybeAdd("bar", calls);

    for (Call<Void> call : calls) {
      call.enqueue(assertFailOnError);
    }
    assertThat(values).containsExactly("foo", "bar");
  }

  @Test public void exceptionsInvalidate_enqueue() {
    List<Call<Void>> calls = new ArrayList<>();
    callFactory.maybeAdd("foo", calls);
    callFactory.maybeAdd("bar", calls);

    failValue.set("foo");

    try {
      calls.get(0).enqueue(assertFailOnError);
      failBecauseExceptionWasNotThrown(AssertionError.class);
    } catch (AssertionError e) {
    }

    calls.get(1).enqueue(assertFailOnError);
    assertThat(values).containsExactly("bar");

    calls.clear();
    callFactory.maybeAdd("foo", calls);
    assertThat(calls).isNotEmpty(); // invalidates on exception

    calls.get(0).enqueue(assertFailOnError);
    assertThat(values).containsExactly("bar", "foo");
  }

  @Test public void exceptionsInvalidate_execute() throws Exception {
    List<Call<Void>> calls = new ArrayList<>();
    callFactory.maybeAdd("foo", calls);
    callFactory.maybeAdd("bar", calls);

    failValue.set("foo");

    try {
      calls.get(0).execute();
      failBecauseExceptionWasNotThrown(AssertionError.class);
    } catch (AssertionError e) {
    }

    calls.get(1).execute();
    assertThat(values).containsExactly("bar");

    calls.clear();
    callFactory.maybeAdd("foo", calls);
    assertThat(calls).isNotEmpty(); // invalidates on exception

    calls.get(0).execute();
    assertThat(values).containsExactly("bar", "foo");
  }

  final class Factory extends DeduplicatingInsert.Factory<String> {
    Factory() {
      super(1000, 1000);
    }

    @Override protected Call<Void> newCall(String string) {
      return new TestDeduplicatingInsert(delayLimiter, string);
    }
  }

  final class TestDeduplicatingInsert extends DeduplicatingInsert<String> {

    TestDeduplicatingInsert(DelayLimiter<String> delayLimiter, String input) {
      super(delayLimiter, input);
    }

    @Override protected CompletionStage<AsyncResultSet> newCompletionStage() {
      if (input.equals(failValue.get())) {
        failValue.set(null);
        CompletableFuture<AsyncResultSet> result = new CompletableFuture<>();
        result.completeExceptionally(new AssertionError());
        return result;
      }
      values.add(input);
      return CompletableFuture.completedFuture(mock(AsyncResultSet.class));
    }

    @Override public Call<Void> clone() {
      return new TestDeduplicatingInsert(delayLimiter, input);
    }
  }
}
