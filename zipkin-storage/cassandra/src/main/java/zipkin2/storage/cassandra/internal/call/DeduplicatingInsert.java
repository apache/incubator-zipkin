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
import java.util.List;
import java.util.concurrent.TimeUnit;
import zipkin2.Call;
import zipkin2.Callback;
import zipkin2.internal.DelayLimiter;

public abstract class DeduplicatingInsert<I> extends ResultSetFutureCall<Void> {
  public static abstract class Factory<I> {
    protected final DelayLimiter<I> delayLimiter;

    protected Factory(long ttl, int cardinality) {
      delayLimiter =
        DelayLimiter.newBuilder().ttl(ttl, TimeUnit.MILLISECONDS).cardinality(cardinality).build();
    }

    protected abstract Call<Void> newCall(I input);

    public final void maybeAdd(I input, List<Call<Void>> calls) {
      if (input == null) throw new NullPointerException("input == null");
      if (!delayLimiter.shouldInvoke(input)) return;
      calls.add(newCall(input));
    }

    public void clear() {
      delayLimiter.clear();
    }
  }

  protected final DelayLimiter<I> delayLimiter;
  protected final I input;

  protected DeduplicatingInsert(DelayLimiter<I> delayLimiter, I input) {
    this.delayLimiter = delayLimiter;
    this.input = input;
  }

  @Override protected final Void doExecute() {
    try {
      return super.doExecute();
    } catch (RuntimeException | Error e) {
      delayLimiter.invalidate(input);
      throw e;
    }
  }

  @Override protected final void doEnqueue(Callback<Void> callback) {
    super.doEnqueue(new Callback<Void>() {
      @Override public void onSuccess(Void value) {
        callback.onSuccess(value);
      }

      @Override public void onError(Throwable t) {
        delayLimiter.invalidate(input);
        callback.onError(t);
      }
    });
  }

  @Override public final void doCancel() {
    delayLimiter.invalidate(input);
    super.doCancel();
  }

  @Override public final Void map(AsyncResultSet input) {
    return null;
  }
}
