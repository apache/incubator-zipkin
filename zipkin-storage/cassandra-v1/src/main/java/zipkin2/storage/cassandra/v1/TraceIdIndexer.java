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

import java.util.AbstractMap.SimpleImmutableEntry;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.DelayQueue;
import java.util.concurrent.Delayed;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zipkin2.internal.Nullable;
import zipkin2.storage.SpanConsumer;
import zipkin2.storage.cassandra.v1.IndexTraceId.Input;

/**
 * This ensures we only attempt to write rows that would extend the timestamp range of a trace index
 * query, by de-duplicating redundant entries upon {@link #iterator()}
 *
 * <p>This is shared singleton as inserts can come from any thread.
 *
 *
 * <h3>Cache cardinality is a soft limit</h3>
 * Deduplication is implemented with a soft bounded cache. The indexer returned by {@link
 * Factory#newIndexer()} doesn't guard size on each call to {@link TraceIdIndexer#add(Input)}, so
 * the cache can grow larger than the cardinality limit while processing a large span. However, this
 * is trimmed back on each message (e.g. POST or incoming Kafka message).
 */
interface TraceIdIndexer extends Iterable<Input> {
  TraceIdIndexer NOOP = new TraceIdIndexer() {
    @Override public void add(Input input) {
    }

    @Override public Iterator<Input> iterator() {
      return Collections.emptyIterator();
    }
  };

  void add(Input input);

  /** This is shared singleton as inserts can come from any thread. */
  class Factory {
    final ConcurrentMap<Map.Entry<String, Long>, Expiration<Map.Entry<String, Long>, Pair>> cache =
      new ConcurrentHashMap<>();
    final DelayQueue<Expiration<Map.Entry<String, Long>, Pair>> expirations = new DelayQueue<>();
    final long ttlNanos;
    final int cardinality;
    final String statement;

    Factory(String statement, long ttlNanos, int cardinality) {
      this.statement = statement;
      this.ttlNanos = ttlNanos;
      this.cardinality = cardinality;
    }

    long nanoTime() {
      return System.nanoTime();
    }

    <K, V> Expiration<K, V> newExpiration(K key, V value) {
      return new Expiration<>(this, key, value, nanoTime() + ttlNanos);
    }

    /**
     * This is called per once per index type in {@link SpanConsumer#accept(List)}. In other words,
     * once per POST or other collected message, regardless of if that message has one or more spans
     * in it. We only enforce cache cardinality here.
     */
    TraceIdIndexer newIndexer() {
      trimCache();
      return new RealTraceIdIndexer(this);
    }

    void trimCache() {
      cleanupExpirations();
      while (cache.size() > cardinality) {
        removeOneExpiration();
      }
    }

    void cleanupExpirations() {
      Expiration<?, ?> expiredexpiration;
      while ((expiredexpiration = expirations.poll()) != null) {
        cache.remove(expiredexpiration.getKey(), expiredexpiration);
      }
    }

    void removeOneExpiration() {
      Expiration<?, ?> eldest;
      while ((eldest = expirations.peek()) != null) { // loop unless empty
        if (expirations.remove(eldest)) { // check for lost race
          cache.remove(eldest.getKey(), eldest);
          break; // to ensure we don't remove two!
        }
      }
    }

    void clear() {
      cache.clear();
      expirations.clear();
    }
  }

  final class Expiration<K, V> extends SimpleImmutableEntry<K, V> implements Delayed {
    final Factory factory;
    final long expiration;

    Expiration(Factory factory, K key, V value, long expiration) {
      super(key, value);
      this.factory = factory;
      this.expiration = expiration;
    }

    @Override public long getDelay(TimeUnit unit) {
      return unit.convert(expiration - factory.nanoTime(), TimeUnit.NANOSECONDS);
    }

    @Override public int compareTo(Delayed o) {
      return Long.signum(expiration - ((Expiration) o).expiration);
    }
  }

  final class RealTraceIdIndexer implements TraceIdIndexer {
    static final Logger LOG = LoggerFactory.getLogger(RealTraceIdIndexer.class);

    final Factory factory;
    final Set<Input> inputs = new LinkedHashSet<>();

    RealTraceIdIndexer(Factory factory) {
      this.factory = factory;
    }

    @Override public void add(Input input) {
      inputs.add(input);
    }

    /**
     * The input may include inserts that already occurred, or are redundant as they don't impact
     * QueryRequest.endTs or QueryRequest.loopback. For example, a parsed timestamp could be between
     * timestamps of rows that already exist for a particular trace. Optimized results will be
     * smaller when the input includes traces with local spans, or when other threads indexed the
     * same trace.
     */
    @Override public Iterator<Input> iterator() {
      Set<Input> result = entriesThatIncreaseGap();
      if (LOG.isDebugEnabled() && inputs.size() > result.size()) {
        int delta = inputs.size() - result.size();
        LOG.debug("optimized out {}/{} inputs to {}", delta, inputs.size(), factory.statement);
      }
      return result.iterator();
    }

    Set<Input> entriesThatIncreaseGap() {
      if (inputs.isEmpty()) return inputs;

      OnChangeUpdateMap toUpdate = new OnChangeUpdateMap(factory);
      Map<Map.Entry<String, Long>, Set<Long>> mappedInputs = new LinkedHashMap<>();

      // Enter a loop that affects shared state when an update widens the time interval for a key.
      for (Input input : inputs) {
        Map.Entry<String, Long> key = toEntry(input);
        long timestamp = input.ts();
        add(mappedInputs, key, timestamp);
        toUpdate.currentTimestamp = timestamp;
        factory.cache.compute(key, toUpdate);
      }

      // When the loop completes, we'll know one of our updates widened the interval of a trace, if
      // it is the first or last timestamp. By ignoring those between an existing interval, we can
      // end up with less Cassandra writes.
      Set<Input> result = new LinkedHashSet<>();
      for (Map.Entry<String, Long> needsUpdate : toUpdate.keySet()) {
        Expiration<Map.Entry<String, Long>, Pair> existing = factory.cache.get(needsUpdate);

        // The bounds of the factory cache are used to prevent out-of-memory issues, but may be
        // accidentally set to a value too low in practice. If we can't find an existing cache
        // entry for the index rows we just say, we still write anyway. If the cache was purged
        // by another indexer, we don't know if our data was written, and it is a better choice
        // to write it vs assume it was already written.
        Pair range = existing != null ? existing.getValue() : toUpdate.get(needsUpdate);

        // check to see if the boundaries of the range corresponded to an input
        if (containsEntry(mappedInputs, needsUpdate, range.left)) {
          result.add(Input.create(needsUpdate.getKey(), range.left, needsUpdate.getValue()));
        }
        if (containsEntry(mappedInputs, needsUpdate, range.right)) {
          result.add(Input.create(needsUpdate.getKey(), range.right, needsUpdate.getValue()));
        }
      }
      return result;
    }

    static SimpleImmutableEntry<String, Long> toEntry(Input input) {
      return new SimpleImmutableEntry<>(input.partitionKey(), input.trace_id());
    }

    static <K, V> void add(Map<K, Set<V>> multimap, K key, V value) {
      multimap.computeIfAbsent(key, unused -> new LinkedHashSet<>()).add(value);
    }

    static <K, V> boolean containsEntry(Map<K, Set<V>> multimap, K key, V value) {
      Set<V> result = multimap.get(key);
      return result != null && result.contains(value);
    }
  }

  /**
   * When a range change occurred during {@link #apply}, we save off the triggering entry for later
   * processing.
   */
  final class OnChangeUpdateMap extends LinkedHashMap<Map.Entry<String, Long>, Pair> implements
    BiFunction<Map.Entry<String, Long>, Expiration<Map.Entry<String, Long>, Pair>, Expiration<Map.Entry<String, Long>, Pair>> {
    final Factory factory;
    long currentTimestamp;

    OnChangeUpdateMap(Factory factory) {
      this.factory = factory;
    }

    @Override public Expiration<Map.Entry<String, Long>, Pair> apply(Map.Entry<String, Long> key,
      @Nullable Expiration<Map.Entry<String, Long>, Pair> oldEntry) {
      Pair oldRange = oldEntry != null ? oldEntry.getValue() : null;
      Pair newRange = null;
      if (oldRange != null) {
        long first = Math.min(currentTimestamp, oldRange.left);
        long last = Math.max(currentTimestamp, oldRange.right);
        if (oldRange.left != first || oldRange.right != last) {
          newRange = new Pair(first, last); // The range was extended
        }
      } else {
        newRange = new Pair(currentTimestamp, currentTimestamp);
      }

      Expiration<Map.Entry<String, Long>, Pair> result;
      if (newRange != null) {
        put(key, newRange);
        result = factory.newExpiration(key, newRange);
      } else {
        // refresh as the current timestamp is contained
        result = factory.newExpiration(key, oldRange);
      }

      // refresh or register a new expiration
      if (oldEntry != null) factory.expirations.remove(oldEntry);
      factory.expirations.add(result);
      return result;
    }
  }
}
