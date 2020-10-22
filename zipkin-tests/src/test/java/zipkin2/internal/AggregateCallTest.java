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
package zipkin2.internal;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;
import zipkin2.Call;
import zipkin2.Callback;
import zipkin2.DependencyLink;

import static java.util.Arrays.asList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.failBecauseExceptionWasNotThrown;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

public class AggregateCallTest {

  @Rule public MockitoRule mocks = MockitoJUnit.rule();

  @Mock Call<Void> call1, call2;
  @Mock Callback<Void> callback;

  @Test(expected = IllegalArgumentException.class)
  public void newVoidCall_emptyNotAllowed() {
    AggregateCall.newVoidCall(asList());
  }

  @Test public void newVoidCall_singletonReturnsOnlyElement() {
    assertThat(AggregateCall.newVoidCall(asList(call1)))
      .isEqualTo(call1);
  }

  @Test public void newVoidCall_joinsMultipleCalls() {
    assertThat(AggregateCall.newVoidCall(asList(call1, call2)))
      .isInstanceOf(AggregateCall.AggregateVoidCall.class)
      .extracting("delegate")
      .isEqualTo(asList(call1, call2));
  }

  @Test public void execute() throws Exception {
    Call<Void> call = AggregateCall.newVoidCall(asList(call1, call2));

    assertThat(call.execute())
      .isNull();

    verify(call1).execute();
    verify(call2).execute();
    verifyNoMoreInteractions(call1, call2);
  }

  @Test public void enqueue() {
    successCallback(call1);
    successCallback(call2);

    Call<Void> call = AggregateCall.newVoidCall(asList(call1, call2));

    call.enqueue(callback);

    verify(callback).onSuccess(null);

    verify(call1).enqueue(any(Callback.class));
    verify(call2).enqueue(any(Callback.class));
    verifyNoMoreInteractions(call1, call2);
  }

  @Test public void enqueue_cancel() {
    call1 = Call.create(null);
    call2 = Call.create(null);

    Call<Void> call = AggregateCall.newVoidCall(asList(call1, call2));
    call.cancel();

    assertThat(call.isCanceled()).isTrue();
    assertThat(call1.isCanceled()).isTrue();
    assertThat(call2.isCanceled()).isTrue();

    call.enqueue(callback);

    verify(callback).onError(isA(IOException.class));
  }

  @Test public void executesOnce() throws Exception {
    Call<Void> call = AggregateCall.newVoidCall(asList(call1, call2));
    call.execute();

    assertThatThrownBy(call::execute)
      .isInstanceOf(IllegalStateException.class);

    assertThatThrownBy(() -> call.enqueue(callback))
      .isInstanceOf(IllegalStateException.class);
  }

  @Test public void enqueuesOnce() {
    Call<Void> call = AggregateCall.newVoidCall(asList(call1, call2));
    call.enqueue(callback);

    assertThatThrownBy(() -> call.enqueue(callback))
      .isInstanceOf(IllegalStateException.class);

    assertThatThrownBy(call::execute)
      .isInstanceOf(IllegalStateException.class);
  }

  @Test public void execute_errorDoesntStopOtherCalls() throws Exception {
    Exception e = new IllegalArgumentException();
    when(call1.execute()).thenThrow(e);
    Call<Void> call = AggregateCall.newVoidCall(asList(call1, call2));

    try {
      call.execute();
      failBecauseExceptionWasNotThrown(e.getClass());
    } catch (IllegalArgumentException ex) {
    }

    verify(call1).execute();
    verify(call2).execute();
    verifyNoMoreInteractions(call1, call2);
  }

  /** This shows that regardless of success or error, we block on completion */
  @Test(timeout = 1000L)
  public void enqueue_blocksOnCompletion() throws InterruptedException {
    CountDownLatch callsLatch = new CountDownLatch(10);
    ExecutorService exec = Executors.newFixedThreadPool(10);

    class LatchCall extends Call.Base<Void> {
      final boolean fail;

      LatchCall(boolean fail) {
        this.fail = fail;
      }

      @Override protected Void doExecute() {
        throw new UnsupportedOperationException();
      }

      @Override protected void doEnqueue(Callback<Void> callback) {
        exec.submit(() -> {
          try {
            callsLatch.await();
          } catch (InterruptedException e) {
            callback.onError(e);
          }
          if (fail) {
            callback.onError(new IOException());
          } else {
            callback.onSuccess(null);
          }
        });
      }

      @Override public Call<Void> clone() {
        return new LatchCall(fail);
      }
    }

    List<Call<Void>> calls = new ArrayList<>();
    for (int i = 0; i < 10; i++) calls.add(new LatchCall(i % 2 == 0));
    Call<Void> call = AggregateCall.newVoidCall(calls);

    AtomicReference<Object> result = new AtomicReference<>();
    call.enqueue(new Callback<Void>() {
      @Override public void onSuccess(Void value) {
        result.set("foo");
      }

      @Override public void onError(Throwable t) {
        result.set(t);
      }
    });

    assertThat(result).hasValue(null);
    callsLatch.countDown(); // one down
    assertThat(result).hasValue(null);
    callsLatch.countDown(); // two down
    assertThat(result).hasValue(null);
    for (int i = 0; i < 8; i++) callsLatch.countDown();

    // wait for threads to finish
    exec.shutdown();
    exec.awaitTermination(1, TimeUnit.SECONDS);

    assertThat(result.get()).isNotNull();
  }

  @Test public void enqueue_errorDoesntStopOtherCalls() {
    Exception e = new IllegalArgumentException();
    errorCallback(call1, e);
    successCallback(call2);

    Call<Void> call = AggregateCall.newVoidCall(asList(call1, call2));

    call.enqueue(callback);

    verify(callback).onError(e);

    verify(call1).enqueue(any(Callback.class));
    verify(call2).enqueue(any(Callback.class));
    verifyNoMoreInteractions(call1, call2);
  }

  static void successCallback(Call<Void> call) {
    doAnswer(a -> {
      ((Callback<Void>) a.getArgument(0)).onSuccess(null);
      return a;
    }).when(call).enqueue(any(Callback.class));
  }

  static void errorCallback(Call<Void> call, Exception e) {
    doAnswer(a -> {
      ((Callback<Void>) a.getArgument(0)).onError(e);
      return a;
    }).when(call).enqueue(any(Callback.class));
  }

  @Test public void execute_finish() throws Exception {
    Call<List<DependencyLink>> call = new AggregateDependencyLinks(asList(
      Call.create(asList(DependencyLink.newBuilder().parent("a").child("b").callCount(1).build())),
      Call.create(asList(DependencyLink.newBuilder().parent("a").child("b").callCount(3).build()))
    ));

    assertThat(call.execute())
      .containsExactly(DependencyLink.newBuilder().parent("a").child("b").callCount(4).build());
  }

  @Test public void enqueue_finish() {
    Call<List<DependencyLink>> call = new AggregateDependencyLinks(asList(
      Call.create(asList(DependencyLink.newBuilder().parent("a").child("b").callCount(1).build())),
      Call.create(asList(DependencyLink.newBuilder().parent("a").child("b").callCount(3).build()))
    ));

    Callback<List<DependencyLink>> callback = mock(Callback.class);

    call.enqueue(callback);

    verify(callback)
      .onSuccess(asList(DependencyLink.newBuilder().parent("a").child("b").callCount(4).build()));
  }

  static final class AggregateDependencyLinks
    extends AggregateCall<List<DependencyLink>, List<DependencyLink>> {
    AggregateDependencyLinks(List<Call<List<DependencyLink>>> calls) {
      super(calls);
    }

    @Override protected List<DependencyLink> newOutput() {
      return new ArrayList<>();
    }

    @Override protected void append(List<DependencyLink> input, List<DependencyLink> output) {
      output.addAll(input);
    }

    // this is the part we are testing, that we can peform a finish step
    @Override protected List<DependencyLink> finish(List<DependencyLink> done) {
      return DependencyLinker.merge(done);
    }

    @Override protected boolean isEmpty(List<DependencyLink> output) {
      return output.isEmpty();
    }

    @Override public AggregateDependencyLinks clone() {
      return new AggregateDependencyLinks(cloneCalls());
    }
  }
}
