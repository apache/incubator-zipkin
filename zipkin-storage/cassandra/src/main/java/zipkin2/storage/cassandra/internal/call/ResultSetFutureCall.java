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

import com.datastax.oss.driver.api.core.DriverException;
import com.datastax.oss.driver.api.core.DriverExecutionException;
import com.datastax.oss.driver.api.core.RequestThrottlingException;
import com.datastax.oss.driver.api.core.connection.BusyConnectionException;
import com.datastax.oss.driver.api.core.cql.AsyncResultSet;
import com.datastax.oss.driver.api.core.servererrors.QueryConsistencyException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.function.BiFunction;
import java.util.function.Function;
import zipkin2.Call;
import zipkin2.Call.Mapper;
import zipkin2.Callback;
import zipkin2.internal.Nullable;

// some copy/pasting is ok here as debugging is obscured when the type hierarchy gets deep.
public abstract class ResultSetFutureCall<V> extends Call.Base<V>
  implements Mapper<AsyncResultSet, V>, Function<AsyncResultSet, V> {
  /** Defers I/O until {@link #enqueue(Callback)} or {@link #execute()} are called. */
  protected abstract CompletionStage<AsyncResultSet> newCompletionStage();

  volatile CompletableFuture<V> future;

  @Override protected V doExecute() {
    return getUninterruptibly(newCompletionStage().thenApply(this));
  }

  @Override protected void doEnqueue(Callback<V> callback) {
    try {
      future = newCompletionStage()
        .thenApply(this)
        .handleAsync(new CallbackFunction<>(callback))
        .toCompletableFuture();
    } catch (Throwable t) {
      propagateIfFatal(t);
      callback.onError(t);
    }
  }

  @Override public V apply(AsyncResultSet input) {
    return map(input); // dispatched to Function so that toString is nicer vs a lambda
  }

  @Override protected void doCancel() {
    CompletableFuture<V> maybeFuture = future;
    if (maybeFuture != null) maybeFuture.cancel(true);
  }

  @Override protected final boolean doIsCanceled() {
    CompletableFuture<V> maybeFuture = future;
    return maybeFuture != null && maybeFuture.isCancelled();
  }

  static final class CallbackFunction<V> implements BiFunction<V, Throwable, V> {
    final Callback<V> callback;

    CallbackFunction(Callback<V> callback) {
      this.callback = callback;
    }

    @Override public V apply(V input, @Nullable Throwable error) {
      if (error != null) {
        callback.onError(error);
        return input;
      }
      try {
        callback.onSuccess(input);
      } catch (Throwable t) {
        propagateIfFatal(t);
        callback.onError(t);
      }
      return input;
    }

    @Override public String toString() {
      return callback.toString();
    }
  }

  // Avoid internal dependency on Datastax CompletableFutures and shaded Throwables
  static <T> T getUninterruptibly(CompletionStage<T> stage) {
    boolean interrupted = false;
    try {
      while (true) {
        try {
          return stage.toCompletableFuture().get();
        } catch (InterruptedException e) {
          interrupted = true;
        } catch (ExecutionException e) {
          Throwable cause = e.getCause();
          if (cause instanceof DriverException) {
            throw ((DriverException) cause).copy();
          }
          if (cause instanceof RuntimeException) throw (RuntimeException) cause;
          if (cause instanceof Error) throw (Error) cause;
          throw new DriverExecutionException(cause);
        }
      }
    } finally {
      if (interrupted) {
        Thread.currentThread().interrupt();
      }
    }
  }

  /**
   * Sets {@link zipkin2.storage.StorageComponent#isOverCapacity(java.lang.Throwable)}
   */
  public static boolean isOverCapacity(Throwable e) {
    return e instanceof QueryConsistencyException ||
      e instanceof BusyConnectionException ||
      e instanceof RequestThrottlingException;
  }
}
