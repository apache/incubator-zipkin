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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;
import zipkin2.Call;
import zipkin2.Callback;

/**
 * A call that blocks on others to complete before invoking a callback or returning from {@link
 * #execute()}. The first error will be returned upstream, later ones will be suppressed.
 *
 * @param <I> the type of returned from {@link Call#execute()}
 * @param <O> the type representing the aggregate success value
 */
public abstract class AggregateCall<I, O> extends Call.Base<O> {

  public static Call<Void> newVoidCall(List<Call<Void>> calls) {
    if (calls.isEmpty()) throw new IllegalArgumentException("calls were empty");
    if (calls.size() == 1) return calls.get(0);
    return new AggregateVoidCall(calls);
  }

  static final class AggregateVoidCall extends AggregateCall<Void, Void> {
    AggregateVoidCall(List<Call<Void>> calls) {
      super(calls);
    }

    volatile boolean empty = true;

    @Override protected Void newOutput() {
      return null;
    }

    @Override protected void append(Void input, Void output) {
      empty = false;
    }

    @Override protected boolean isEmpty(Void output) {
      return empty;
    }

    @Override public AggregateVoidCall clone() {
      return new AggregateVoidCall(cloneCalls());
    }
  }

  final Logger log = Logger.getLogger(getClass().getName());
  final List<Call<I>> delegate;

  protected AggregateCall(List<Call<I>> delegate) {
    assert !delegate.isEmpty() : "do not create empty aggregate calls";
    assert delegate.size() > 1 : "do not create single-element aggregates";
    this.delegate = delegate;
  }

  protected abstract O newOutput();

  protected abstract void append(I input, O output);

  protected abstract boolean isEmpty(O output);

  /** Customizes the aggregated result. For example, summarizing or making immutable. */
  protected O finish(O output) {
    return output;
  }

  @Override protected O doExecute() throws IOException {
    int length = delegate.size();
    Throwable firstError = null;
    O result = newOutput();
    for (int i = 0; i < length; i++) {
      Call<I> call = delegate.get(i);
      try {
        append(call.execute(), result);
      } catch (Throwable e) {
        if (firstError == null) {
          firstError = e;
        } else if (log.isLoggable(Level.INFO)) {
          log.log(Level.INFO, "error from " + call, e);
        }
      }
    }
    if (firstError == null) return finish(result);
    if (firstError instanceof Error) throw (Error) firstError;
    if (firstError instanceof RuntimeException) throw (RuntimeException) firstError;
    throw (IOException) firstError;
  }

  @Override protected void doEnqueue(Callback<O> callback) {
    int length = delegate.size();
    AtomicInteger remaining = new AtomicInteger(length);
    AtomicReference firstError = new AtomicReference();
    O result = newOutput();
    for (int i = 0; i < length; i++) {
      Call<I> call = delegate.get(i);
      call.enqueue(new CountdownCallback(call, remaining, firstError, result, callback));
    }
  }

  @Override protected void doCancel() {
    for (int i = 0, length = delegate.size(); i < length; i++) {
      delegate.get(i).cancel();
    }
  }

  class CountdownCallback implements Callback<I> {
    final Call<I> call;
    final AtomicInteger remaining;
    final AtomicReference<Throwable> firstError;
    @Nullable final O result;
    final Callback<O> callback;

    CountdownCallback(Call<I> call, AtomicInteger remaining, AtomicReference<Throwable> firstError,
      O result,
      Callback<O> callback) {
      this.call = call;
      this.remaining = remaining;
      this.firstError = firstError;
      this.result = result;
      this.callback = callback;
    }

    @Override public void onSuccess(I value) {
      synchronized (callback) {
        append(value, result);
        if (remaining.decrementAndGet() > 0) return;
        Throwable error = firstError.get();
        if (error != null) {
          callback.onError(error);
        } else {
          callback.onSuccess(finish(result));
        }
      }
    }

    @Override public synchronized void onError(Throwable throwable) {
      if (log.isLoggable(Level.INFO)) {
        log.log(Level.INFO, "error from " + call, throwable);
      }
      synchronized (callback) {
        firstError.compareAndSet(null, throwable);
        if (remaining.decrementAndGet() > 0) return;
        callback.onError(firstError.get());
      }
    }
  }

  protected final List<Call<I>> cloneCalls() {
    int length = delegate.size();
    List<Call<I>> result = new ArrayList<Call<I>>(length);
    for (int i = 0; i < length; i++) {
      result.add(delegate.get(i).clone());
    }
    return result;
  }

  public final List<Call<I>> delegate() {
    return delegate;
  }

  @Override public String toString() {
    return "AggregateCall{" + delegate + "}";
  }
}
