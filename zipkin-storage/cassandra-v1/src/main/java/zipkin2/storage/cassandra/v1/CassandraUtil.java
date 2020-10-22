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

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import zipkin2.Annotation;
import zipkin2.Call;
import zipkin2.Span;
import zipkin2.internal.DateUtil;
import zipkin2.internal.Nullable;
import zipkin2.internal.RecyclableBuffers;
import zipkin2.storage.QueryRequest;

import static zipkin2.internal.RecyclableBuffers.SHORT_STRING_LENGTH;

final class CassandraUtil {
  static final List<String> CORE_ANNOTATIONS =
    Arrays.asList("cs", "cr", "ss", "sr", "ms", "mr", "ws", "wr");

  private static final ThreadLocal<CharsetEncoder> UTF8_ENCODER =
    ThreadLocal.withInitial(StandardCharsets.UTF_8::newEncoder);

  static ByteBuffer toByteBuffer(String string) {
    try {
      return UTF8_ENCODER.get().encode(CharBuffer.wrap(string));
    } catch (CharacterCodingException e) {
      throw new IllegalArgumentException(e);
    }
  }

  /**
   * Returns keys that concatenate the serviceName associated with an annotation or tag.
   *
   * <p>Values over {@link RecyclableBuffers#SHORT_STRING_LENGTH} are not considered. Zipkin's
   * {@link QueryRequest#annotationQuery()} are equals match. Not all values are lookup values. For
   * example, {@code sql.query} isn't something that is likely to be looked up by value and indexing
   * that could add a potentially kilobyte partition key on {@link Tables#ANNOTATIONS_INDEX}
   *
   * @see QueryRequest#annotationQuery()
   */
  static Set<String> annotationKeys(Span span) {
    Set<String> annotationKeys = new LinkedHashSet<>();
    String localServiceName = span.localServiceName();
    if (localServiceName == null) return annotationKeys;
    for (Annotation a : span.annotations()) {
      if (a.value().length() > SHORT_STRING_LENGTH) continue;

      // don't index core annotations as they aren't queryable
      if (CORE_ANNOTATIONS.contains(a.value())) continue;
      annotationKeys.add(localServiceName + ":" + a.value());
    }
    for (Map.Entry<String, String> e : span.tags().entrySet()) {
      if (e.getValue().length() > SHORT_STRING_LENGTH) continue;

      annotationKeys.add(localServiceName + ":" + e.getKey());
      annotationKeys.add(localServiceName + ":" + e.getKey() + ":" + e.getValue());
    }
    return annotationKeys;
  }

  static List<String> annotationKeys(QueryRequest request) {
    if (request.annotationQuery().isEmpty()) return Collections.emptyList();
    if (request.serviceName() == null) {
      throw new IllegalArgumentException("serviceName needed with annotation query");
    }
    Set<String> annotationKeys = new LinkedHashSet<>();
    for (Map.Entry<String, String> e : request.annotationQuery().entrySet()) {
      if (e.getValue().isEmpty()) {
        annotationKeys.add(request.serviceName() + ":" + e.getKey());
      } else {
        annotationKeys.add(request.serviceName() + ":" + e.getKey() + ":" + e.getValue());
      }
    }
    return sortedList(annotationKeys);
  }

  static <T extends Comparable<? super T>> List<T> sortedList(@Nullable Collection<T> in) {
    if (in == null || in.isEmpty()) return Collections.emptyList();
    if (in.size() == 1) return Collections.singletonList(in.iterator().next());
    Object[] array = in.toArray();
    Arrays.sort(array);
    List result = Arrays.asList(array);
    return Collections.unmodifiableList(result);
  }

  static final Random RAND = new Random(System.nanoTime());
  static final BigInteger OFFSET = BigInteger.valueOf(Integer.MAX_VALUE);

  static Set<Long> sortTraceIdsByDescTimestamp(Set<Pair> set) {
    // timestamps can collide, so we need to add some random digits on end before using them as
    // serviceSpanKeys
    TreeMap<BigInteger, Long> sorted = new TreeMap<>(Collections.reverseOrder());
    for (Pair pair : set) {
      BigInteger uncollided =
        BigInteger.valueOf(pair.right)
          .multiply(OFFSET)
          .add(BigInteger.valueOf(RAND.nextInt() & Integer.MAX_VALUE));
      sorted.put(uncollided, pair.left);
    }
    return new LinkedHashSet<>(sorted.values());
  }

  static Call.Mapper<Set<Pair>, Set<Long>> sortTraceIdsByDescTimestampMapper() {
    return SortTraceIdsByDescTimestamp.INSTANCE;
  }

  enum SortTraceIdsByDescTimestamp implements Call.Mapper<Set<Pair>, Set<Long>> {
    INSTANCE;

    @Override public Set<Long> map(Set<Pair> set) {
      return sortTraceIdsByDescTimestamp(set);
    }

    @Override public String toString() {
      return "SortTraceIdsByDescTimestamp";
    }
  }

  @SuppressWarnings("JdkObsolete")
  static List<Instant> getDays(long endTs, @Nullable Long lookback) {
    List<Instant> result = new ArrayList<>();
    for (long epochMillis : DateUtil.epochDays(endTs, lookback)) {
      result.add(Instant.ofEpochMilli(epochMillis));
    }
    return result;
  }
}
