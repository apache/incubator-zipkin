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

import com.datastax.oss.driver.api.core.RequestThrottlingException;
import com.datastax.oss.driver.api.core.connection.BusyConnectionException;
import com.datastax.oss.driver.api.core.cql.AsyncResultSet;
import com.datastax.oss.driver.api.core.servererrors.QueryConsistencyException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.junit.Test;
import zipkin2.Call;
import zipkin2.Callback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

public class ResultSetFutureCallTest {
  CompletableFuture<AsyncResultSet> future = new CompletableFuture<>();
  AsyncResultSet resultSet = mock(AsyncResultSet.class);

  ResultSetFutureCall<AsyncResultSet> call = new ResultSetFutureCall<AsyncResultSet>() {
    @Override protected CompletionStage<AsyncResultSet> newCompletionStage() {
      return ResultSetFutureCallTest.this.future;
    }

    @Override public Call<AsyncResultSet> clone() {
      return null;
    }

    @Override public AsyncResultSet map(AsyncResultSet input) {
      return input;
    }
  };

  static final class CompletableCallback<T> extends CompletableFuture<T> implements Callback<T> {
    @Override public void onSuccess(T value) {
      complete(value);
    }

    @Override public void onError(Throwable t) {
      completeExceptionally(t);
    }
  }

  CompletableCallback<AsyncResultSet> callback = new CompletableCallback<>();

  @Test public void enqueue_cancel_beforeCreateFuture() {
    call.cancel();

    assertThat(call.isCanceled()).isTrue();
  }

  @Test public void enqueue_callsFutureGet() throws Exception {
    call.enqueue(callback);

    future.complete(resultSet);

    assertThat(callback.get()).isEqualTo(resultSet);
  }

  @Test public void enqueue_cancel_afterEnqueue() {
    call.enqueue(callback);
    call.cancel();

    assertThat(call.isCanceled()).isTrue();
    // this.future will be wrapped, so can't check if that is canceled.
    assertThat(call.future.isCancelled()).isTrue();
  }

  @Test public void enqueue_callbackError_onErrorCreatingFuture() {
    IllegalArgumentException error = new IllegalArgumentException();
    call = new ResultSetFutureCall<AsyncResultSet>() {
      @Override protected CompletionStage<AsyncResultSet> newCompletionStage() {
        throw error;
      }

      @Override public Call<AsyncResultSet> clone() {
        return null;
      }

      @Override public AsyncResultSet map(AsyncResultSet input) {
        return input;
      }
    };

    call.enqueue(callback);

    // ensure the callback received the exception
    assertThat(callback.isCompletedExceptionally()).isTrue();
    assertThatThrownBy(callback::get).hasCause(error);
  }

  // below are load related exceptions which should result in a backoff of storage requests
  @Test public void isOverCapacity() {
    assertThat(ResultSetFutureCall.isOverCapacity(
      new RequestThrottlingException("The session is shutting down"))).isTrue();
    assertThat(ResultSetFutureCall.isOverCapacity(new BusyConnectionException(100))).isTrue();
    assertThat(ResultSetFutureCall.isOverCapacity(mock(QueryConsistencyException.class))).isTrue();

    // not applicable
    assertThat(ResultSetFutureCall.isOverCapacity(
      new IllegalStateException("Rejected execution"))).isFalse();
  }
}
