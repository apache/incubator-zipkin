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
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import zipkin2.Call;
import zipkin2.Callback;
import zipkin2.DependencyLink;
import zipkin2.Endpoint;
import zipkin2.Span;
import zipkin2.internal.DependencyLinker;

/**
 * Test storage component that keeps all spans in memory, accepting them on the calling thread.
 *
 * <p>Internally, spans are indexed on 64-bit trace ID
 *
 * <p>Here's an example of some traces in memory:
 *
 * <pre>{@code
 * spansByTraceIdTimeStamp:
 *    <aaaa,July 4> --> ( spanA(time:July 4, traceId:aaaa, service:foo, name:GET),
 *                        spanB(time:July 4, traceId:aaaa, service:bar, name:GET) )
 *    <cccc,July 4> --> ( spanC(time:July 4, traceId:aaaa, service:foo, name:GET) )
 *    <bbbb,July 5> --> ( spanD(time:July 5, traceId:bbbb, service:biz, name:GET) )
 *    <bbbb,July 6> --> ( spanE(time:July 6, traceId:bbbb) service:foo, name:POST )
 *
 * traceIdToTraceIdTimeStamps:
 *    aaaa --> [ <aaaa,July 4> ]
 *    bbbb --> [ <bbbb,July 5>, <bbbb,July 6> ]
 *    cccc --> [ <cccc,July 4> ]
 *
 * serviceToTraceIds:
 *    foo --> [ <aaaa>, <cccc>, <bbbb> ]
 *    bar --> [ <aaaa> ]
 *    biz --> [ <bbbb> ]
 *
 * serviceToSpanNames:
 *    bar --> ( GET )
 *    biz --> ( GET )
 *    foo --> ( GET, POST )
 * }</pre>
 */
public final class InMemoryStorage extends StorageComponent implements SpanStore, SpanConsumer,
  AutocompleteTags, ServiceAndSpanNames, Traces {

  public static Builder newBuilder() {
    return new Builder();
  }

  public static final class Builder extends StorageComponent.Builder {
    boolean strictTraceId = true, searchEnabled = true;
    int maxSpanCount = 500000;
    List<String> autocompleteKeys = Collections.emptyList();

    @Override public Builder strictTraceId(boolean strictTraceId) {
      this.strictTraceId = strictTraceId;
      return this;
    }

    @Override public Builder searchEnabled(boolean searchEnabled) {
      this.searchEnabled = searchEnabled;
      return this;
    }

    @Override public Builder autocompleteKeys(List<String> autocompleteKeys) {
      if (autocompleteKeys == null) throw new NullPointerException("autocompleteKeys == null");
      this.autocompleteKeys = autocompleteKeys;
      return this;
    }

    /** Eldest traces are removed to ensure spans in memory don't exceed this value */
    public Builder maxSpanCount(int maxSpanCount) {
      if (maxSpanCount <= 0) throw new IllegalArgumentException("maxSpanCount <= 0");
      this.maxSpanCount = maxSpanCount;
      return this;
    }

    @Override public InMemoryStorage build() {
      return new InMemoryStorage(this);
    }
  }

  /**
   * Primary source of data is this map, which includes spans ordered descending by timestamp. All
   * other maps are derived from the span values here. This uses a list for the spans, so that it is
   * visible (via /api/v2/trace/{traceId}) when instrumentation report the same spans multiple
   * times.
   */
  private final SortedMultimap<TraceIdTimestamp, Span> spansByTraceIdTimestamp =
    new SortedMultimap<TraceIdTimestamp, Span>(TIMESTAMP_DESCENDING) {
      @Override Collection<Span> valueContainer() {
        return new LinkedHashSet<Span>();
      }
    };

  /** This supports span lookup by {@link Span#traceId() lower 64-bits of the trace ID} */
  private final SortedMultimap<String, TraceIdTimestamp> traceIdToTraceIdTimestamps =
    new SortedMultimap<String, TraceIdTimestamp>(STRING_COMPARATOR) {
      @Override Collection<TraceIdTimestamp> valueContainer() {
        return new LinkedHashSet<TraceIdTimestamp>();
      }
    };
  /** This is an index of {@link Span#traceId()} by {@link Endpoint#serviceName() service name} */
  private final ServiceNameToTraceIds serviceToTraceIds = new ServiceNameToTraceIds();
  /** This is an index of {@link Span#name()} by {@link Endpoint#serviceName() service name} */
  private final SortedMultimap<String, String> serviceToSpanNames =
    new SortedMultimap<String, String>(STRING_COMPARATOR) {
      @Override Collection<String> valueContainer() {
        return new LinkedHashSet<String>();
      }
    };
  /**
   * This is an index of {@link Span#remoteServiceName()} by {@link Endpoint#serviceName() service
   * name}
   */
  private final SortedMultimap<String, String> serviceToRemoteServiceNames =
    new SortedMultimap<String, String>(STRING_COMPARATOR) {
      @Override Collection<String> valueContainer() {
        return new LinkedHashSet<String>();
      }
    };

  private final SortedMultimap<String, String> autocompleteTags =
    new SortedMultimap<String, String>(STRING_COMPARATOR) {
      @Override Collection<String> valueContainer() {
        return new LinkedHashSet<String>();
      }
    };

  final boolean strictTraceId, searchEnabled;
  final int maxSpanCount;
  final Call<List<String>> autocompleteKeysCall;
  final Set<String> autocompleteKeys;
  final AtomicInteger acceptedSpanCount = new AtomicInteger();

  InMemoryStorage(Builder builder) {
    this.strictTraceId = builder.strictTraceId;
    this.searchEnabled = builder.searchEnabled;
    this.maxSpanCount = builder.maxSpanCount;
    this.autocompleteKeysCall = Call.create(builder.autocompleteKeys);
    this.autocompleteKeys = new LinkedHashSet<String>(builder.autocompleteKeys);
  }

  public int acceptedSpanCount() {
    return acceptedSpanCount.get();
  }

  public synchronized void clear() {
    acceptedSpanCount.set(0);
    traceIdToTraceIdTimestamps.clear();
    spansByTraceIdTimestamp.clear();
    serviceToTraceIds.clear();
    serviceToRemoteServiceNames.clear();
    serviceToSpanNames.clear();
    autocompleteTags.clear();
  }

  @Override public Call<Void> accept(List<Span> spans) {
    return new StoreSpansCall(spans);
  }

  synchronized void doAccept(List<Span> spans) {
    int delta = spans.size();
    acceptedSpanCount.addAndGet(delta);

    int spansToRecover = (spansByTraceIdTimestamp.size() + delta) - maxSpanCount;
    evictToRecoverSpans(spansToRecover);
    for (Span span : spans) {
      long timestamp = span.timestampAsLong() / 1000L;
      String lowTraceId = lowTraceId(span.traceId());
      TraceIdTimestamp traceIdTimeStamp = new TraceIdTimestamp(lowTraceId, timestamp);
      spansByTraceIdTimestamp.put(traceIdTimeStamp, span);
      traceIdToTraceIdTimestamps.put(lowTraceId, traceIdTimeStamp);

      if (!searchEnabled) continue;
      String serviceName = span.localServiceName();
      if (serviceName != null) {
        serviceToTraceIds.put(serviceName, lowTraceId);
        String remoteServiceName = span.remoteServiceName();
        if (remoteServiceName != null) {
          serviceToRemoteServiceNames.put(serviceName, remoteServiceName);
        }
        String spanName = span.name();
        if (spanName != null) {
          serviceToSpanNames.put(serviceName, spanName);
        }
      }
      for (Map.Entry<String, String> tag : span.tags().entrySet()) {
        if (autocompleteKeys.contains(tag.getKey())) {
          autocompleteTags.put(tag.getKey(), tag.getValue());
        }
      }
    }
  }

  final class StoreSpansCall extends Call.Base<Void> {
    final List<Span> spans;

    StoreSpansCall(List<Span> spans) {
      this.spans = spans;
    }

    @Override protected Void doExecute() {
      doAccept(spans);
      return null;
    }

    @Override protected void doEnqueue(Callback<Void> callback) {
      try {
        callback.onSuccess(doExecute());
      } catch (Throwable t) {
        propagateIfFatal(t);
        callback.onError(t);
      }
    }

    @Override public Call<Void> clone() {
      return new StoreSpansCall(spans);
    }

    @Override public String toString() {
      return "StoreSpansCall{" + spans + "}";
    }
  }

  /** Returns the count of spans evicted. */
  int evictToRecoverSpans(int spansToRecover) {
    int spansEvicted = 0;
    while (spansToRecover > 0) {
      int spansInOldestTrace = deleteOldestTrace();
      spansToRecover -= spansInOldestTrace;
      spansEvicted += spansInOldestTrace;
    }
    return spansEvicted;
  }

  /** Returns the count of spans evicted. */
  private int deleteOldestTrace() {
    int spansEvicted = 0;
    String lowTraceId = spansByTraceIdTimestamp.delegate.lastKey().lowTraceId;
    Collection<TraceIdTimestamp> traceIdTimeStamps = traceIdToTraceIdTimestamps.remove(lowTraceId);
    for (Iterator<TraceIdTimestamp> traceIdTimeStampIter = traceIdTimeStamps.iterator();
      traceIdTimeStampIter.hasNext(); ) {
      TraceIdTimestamp traceIdTimeStamp = traceIdTimeStampIter.next();
      Collection<Span> spans = spansByTraceIdTimestamp.remove(traceIdTimeStamp);
      spansEvicted += spans.size();
    }
    if (searchEnabled) {
      for (String orphanedService : serviceToTraceIds.removeServiceIfTraceId(lowTraceId)) {
        serviceToRemoteServiceNames.remove(orphanedService);
        serviceToSpanNames.remove(orphanedService);
      }
    }
    return spansEvicted;
  }

  @Override public Call<List<List<Span>>> getTraces(QueryRequest request) {
    return getTraces(request, strictTraceId);
  }

  synchronized Call<List<List<Span>>> getTraces(QueryRequest request, boolean strictTraceId) {
    Set<String> lowTraceIdsInRange = traceIdsDescendingByTimestamp(request);
    if (lowTraceIdsInRange.isEmpty()) return Call.emptyList();

    List<List<Span>> result = new ArrayList<List<Span>>();
    for (Iterator<String> lowTraceId = lowTraceIdsInRange.iterator();
      lowTraceId.hasNext() && result.size() < request.limit(); ) {
      List<Span> next = spansByTraceId(lowTraceId.next());
      if (!request.test(next)) continue;
      if (!strictTraceId) {
        result.add(next);
        continue;
      }

      // re-run the query as now spans are strictly grouped
      for (List<Span> strictTrace : strictByTraceId(next)) {
        if (request.test(strictTrace)) result.add(strictTrace);
      }
    }

    return Call.create(result);
  }

  static Collection<List<Span>> strictByTraceId(List<Span> next) {
    Map<String, List<Span>> groupedByTraceId = new LinkedHashMap<String, List<Span>>();
    for (Span span : next) {
      String traceId = span.traceId();
      if (!groupedByTraceId.containsKey(traceId)) {
        groupedByTraceId.put(traceId, new ArrayList<Span>());
      }
      groupedByTraceId.get(traceId).add(span);
    }
    return groupedByTraceId.values();
  }

  /** Used for testing. Returns all traces unconditionally. */
  public synchronized List<List<Span>> getTraces() {
    List<List<Span>> result = new ArrayList<List<Span>>();
    for (String lowTraceId : traceIdToTraceIdTimestamps.keySet()) {
      List<Span> sameTraceId = spansByTraceId(lowTraceId);
      if (strictTraceId) {
        result.addAll(strictByTraceId(sameTraceId));
      } else {
        result.add(sameTraceId);
      }
    }
    return result;
  }

  /** Used for testing. Returns all dependency links unconditionally. */
  public synchronized List<DependencyLink> getDependencies() {
    return getDependencyLinks(traceIdToTraceIdTimestamps.keySet());
  }

  Set<String> traceIdsDescendingByTimestamp(QueryRequest request) {
    if (!searchEnabled) return Collections.emptySet();

    Collection<TraceIdTimestamp> traceIdTimestamps =
      request.serviceName() != null
        ? traceIdTimestampsByServiceName(request.serviceName())
        : spansByTraceIdTimestamp.keySet();

    if (traceIdTimestamps == null || traceIdTimestamps.isEmpty()) return Collections.emptySet();

    return lowTraceIdsInRange(traceIdTimestamps, request.endTs, request.lookback);
  }

  static Set<String> lowTraceIdsInRange(
    Collection<TraceIdTimestamp> descendingByTimestamp, long endTs, long lookback) {
    long beginTs = endTs - lookback;
    Set<String> result = new LinkedHashSet<String>();
    for (TraceIdTimestamp traceIdTimestamp : descendingByTimestamp) {
      if (traceIdTimestamp.timestamp >= beginTs && traceIdTimestamp.timestamp <= endTs) {
        result.add(traceIdTimestamp.lowTraceId);
      }
    }
    return Collections.unmodifiableSet(result);
  }

  @Override public synchronized Call<List<Span>> getTrace(String traceId) {
    traceId = Span.normalizeTraceId(traceId);
    List<Span> spans = spansByTraceId(lowTraceId(traceId));
    if (spans.isEmpty()) return Call.emptyList();
    if (!strictTraceId) return Call.create(spans);

    List<Span> filtered = new ArrayList<Span>(spans);
    Iterator<Span> iterator = filtered.iterator();
    while (iterator.hasNext()) {
      if (!iterator.next().traceId().equals(traceId)) {
        iterator.remove();
      }
    }
    return Call.create(filtered);
  }

  @Override public synchronized Call<List<List<Span>>> getTraces(Iterable<String> traceIds) {
    Set<String> normalized = new LinkedHashSet<String>();
    for (String traceId : traceIds) {
      normalized.add(Span.normalizeTraceId(traceId));
    }

    // Our index is by lower-64 bit trace ID, so let's build trace IDs to fetch
    Set<String> lower64Bit = new LinkedHashSet<String>();
    for (String traceId : normalized) {
      lower64Bit.add(lowTraceId(traceId));
    }

    List<List<Span>> result = new ArrayList<List<Span>>();
    for (String lowTraceId : lower64Bit) {
      List<Span> sameTraceId = spansByTraceId(lowTraceId);
      if (strictTraceId) {
        for (List<Span> trace : strictByTraceId(sameTraceId)) {
          if (normalized.contains(trace.get(0).traceId())) {
            result.add(trace);
          }
        }
      } else {
        result.add(sameTraceId);
      }
    }

    return Call.create(result);
  }

  @Override public synchronized Call<List<String>> getServiceNames() {
    if (!searchEnabled) return Call.emptyList();
    return Call.<List<String>>create(new ArrayList<String>(serviceToTraceIds.keySet()));
  }

  @Override public synchronized Call<List<String>> getRemoteServiceNames(String service) {
    if (service.isEmpty() || !searchEnabled) return Call.emptyList();
    service = service.toLowerCase(Locale.ROOT); // service names are always lowercase!
    return Call.<List<String>>create(
      new ArrayList<String>(serviceToRemoteServiceNames.get(service)));
  }

  @Override public synchronized Call<List<String>> getSpanNames(String service) {
    if (service.isEmpty() || !searchEnabled) return Call.emptyList();
    service = service.toLowerCase(Locale.ROOT); // service names are always lowercase!
    return Call.<List<String>>create(new ArrayList<String>(serviceToSpanNames.get(service)));
  }

  @Override
  public synchronized Call<List<DependencyLink>> getDependencies(long endTs, long lookback) {
    if (endTs <= 0) throw new IllegalArgumentException("endTs <= 0");
    if (lookback <= 0) throw new IllegalArgumentException("lookback <= 0");

    Set<String> lowTraceIdsInRange =
      lowTraceIdsInRange(spansByTraceIdTimestamp.keySet(), endTs, lookback);
    List<DependencyLink> links = getDependencyLinks(lowTraceIdsInRange);
    return Call.create(links);
  }

  // We don't have a query parameter for strictTraceId when fetching dependency links, so we
  // ignore traceIdHigh. Otherwise, a single trace can appear as two, doubling callCount.
  List<DependencyLink> getDependencyLinks(Set<String> lowTraceIdsInRange) {
    if (lowTraceIdsInRange.isEmpty()) return Collections.emptyList();
    DependencyLinker linksBuilder = new DependencyLinker();
    for (String lowTraceId : lowTraceIdsInRange) {
      linksBuilder.putTrace(spansByTraceId(lowTraceId));
    }
    return linksBuilder.link();
  }

  @Override public synchronized Call<List<String>> getKeys() {
    if (!searchEnabled) return Call.emptyList();
    return autocompleteKeysCall.clone();
  }

  @Override public synchronized Call<List<String>> getValues(String key) {
    if (key == null) throw new NullPointerException("key == null");
    if (key.isEmpty()) throw new IllegalArgumentException("key was empty");
    if (!searchEnabled) return Call.emptyList();
    return Call.<List<String>>create(new ArrayList<String>(autocompleteTags.get(key)));
  }

  static final Comparator<String> STRING_COMPARATOR = new Comparator<String>() {
    @Override public int compare(String left, String right) {
      if (left == null) return -1;
      return left.compareTo(right);
    }

    @Override public String toString() {
      return "String::compareTo";
    }
  };

  static final Comparator<TraceIdTimestamp> TIMESTAMP_DESCENDING =
    new Comparator<TraceIdTimestamp>() {
      @Override public int compare(TraceIdTimestamp left, TraceIdTimestamp right) {
        long x = left.timestamp, y = right.timestamp;
        int result = (x < y) ? -1 : ((x == y) ? 0 : 1); // Long.compareTo is JRE 7+
        if (result != 0) return -result; // use negative as we are descending
        return right.lowTraceId.compareTo(left.lowTraceId);
      }

      @Override public String toString() {
        return "TimestampDescending{}";
      }
    };

  static final class ServiceNameToTraceIds extends SortedMultimap<String, String> {
    ServiceNameToTraceIds() {
      super(STRING_COMPARATOR);
    }

    @Override Set<String> valueContainer() {
      return new LinkedHashSet<String>();
    }

    /** Returns service names orphaned by removing the trace ID */
    Set<String> removeServiceIfTraceId(String lowTraceId) {
      Set<String> result = new LinkedHashSet<String>();
      for (Map.Entry<String, Collection<String>> entry : delegate.entrySet()) {
        Collection<String> lowTraceIds = entry.getValue();
        if (lowTraceIds.remove(lowTraceId) && lowTraceIds.isEmpty()) {
          result.add(entry.getKey());
        }
      }
      delegate.keySet().removeAll(result);
      return result;
    }
  }

  // Not synchronized as every exposed method on the enclosing type is
  abstract static class SortedMultimap<K, V> {
    final SortedMap<K, Collection<V>> delegate;
    int size = 0;

    SortedMultimap(Comparator<K> comparator) {
      delegate = new TreeMap<K, Collection<V>>(comparator);
    }

    abstract Collection<V> valueContainer();

    Set<K> keySet() {
      return delegate.keySet();
    }

    int size() {
      return size;
    }

    void put(K key, V value) {
      Collection<V> valueContainer = delegate.get(key);
      if (valueContainer == null) {
        delegate.put(key, valueContainer = valueContainer());
      }
      if (valueContainer.add(value)) size++;
    }

    Collection<V> remove(K key) {
      Collection<V> value = delegate.remove(key);
      if (value != null) size -= value.size();
      return value;
    }

    void clear() {
      delegate.clear();
      size = 0;
    }

    Collection<V> get(K key) {
      Collection<V> result = delegate.get(key);
      return result != null ? result : Collections.<V>emptySet();
    }
  }

  List<Span> spansByTraceId(String lowTraceId) {
    List<Span> sameTraceId = new ArrayList<Span>();
    for (TraceIdTimestamp traceIdTimestamp : traceIdToTraceIdTimestamps.get(lowTraceId)) {
      sameTraceId.addAll(spansByTraceIdTimestamp.get(traceIdTimestamp));
    }
    return sameTraceId;
  }

  Collection<TraceIdTimestamp> traceIdTimestampsByServiceName(String serviceName) {
    List<TraceIdTimestamp> traceIdTimestamps = new ArrayList<TraceIdTimestamp>();
    for (String lowTraceId : serviceToTraceIds.get(serviceName)) {
      traceIdTimestamps.addAll(traceIdToTraceIdTimestamps.get(lowTraceId));
    }
    Collections.sort(traceIdTimestamps, TIMESTAMP_DESCENDING);
    return traceIdTimestamps;
  }

  static String lowTraceId(String traceId) {
    return traceId.length() == 32 ? traceId.substring(16) : traceId;
  }

  @Override public InMemoryStorage traces() {
    return this;
  }

  @Override public InMemoryStorage spanStore() {
    return this;
  }

  @Override public InMemoryStorage autocompleteTags() {
    return this;
  }

  @Override public InMemoryStorage serviceAndSpanNames() {
    return this;
  }

  @Override public SpanConsumer spanConsumer() {
    return this;
  }

  @Override public void close() {
  }

  static final class TraceIdTimestamp {
    final String lowTraceId;
    final long timestamp;

    TraceIdTimestamp(String lowTraceId, long timestamp) {
      this.lowTraceId = lowTraceId;
      this.timestamp = timestamp;
    }

    @Override public boolean equals(Object o) {
      if (o == this) return true;
      if (!(o instanceof TraceIdTimestamp)) return false;
      TraceIdTimestamp that = (TraceIdTimestamp) o;
      return lowTraceId.equals(that.lowTraceId) && timestamp == that.timestamp;
    }

    @Override public int hashCode() {
      int h$ = 1;
      h$ *= 1000003;
      h$ ^= lowTraceId.hashCode();
      h$ *= 1000003;
      h$ ^= (int) ((timestamp >>> 32) ^ timestamp);
      return h$;
    }
  }

  @Override public String toString() {
    return "InMemoryStorage{}";
  }
}
