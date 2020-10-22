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

import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import zipkin2.Annotation;
import zipkin2.Span;
import zipkin2.internal.Nullable;

/**
 * Invoking this request retrieves traces matching the below filters.
 *
 * <p>Results should be filtered against {@link #endTs}, subject to {@link #limit} and {@link
 * #lookback}. For example, if endTs is 10:20 today, limit is 10, and lookback is 7 days, traces
 * returned should be those nearest to 10:20 today, not 10:20 a week ago.
 *
 * <p>Time units of {@link #endTs} and {@link #lookback} are milliseconds as opposed to
 * microseconds, the grain of {@link Span#timestamp()}. Milliseconds is a more familiar and
 * supported granularity for query, index and windowing functions.
 */
public final class QueryRequest {
  /**
   * When present, corresponds to the {@link Span#localServiceName() local service name} and
   * constrains all other parameters.
   *
   * @see ServiceAndSpanNames#getServiceNames()
   */
  @Nullable public String serviceName() {
    return serviceName;
  }

  /**
   * When present, only include traces with this {@link Span#remoteServiceName() remote service
   * name}.
   *
   * @see ServiceAndSpanNames#getRemoteServiceNames(String)
   */
  @Nullable public String remoteServiceName() {
    return remoteServiceName;
  }

  /**
   * When present, only include traces with this {@link Span#name()}
   *
   * @see ServiceAndSpanNames#getSpanNames(String)
   */
  @Nullable public String spanName() {
    return spanName;
  }

  /**
   * When an input value is the empty string, include traces whose {@link Span#annotations()}
   * include a value in this set, or where {@link Span#tags()} include a key is in this set. When
   * not, include traces whose {@link Span#tags()} an entry in this map.
   *
   * <p>Multiple entries are combined with AND, and AND against other conditions.
   */
  public Map<String, String> annotationQuery() {
    return annotationQuery;
  }

  /**
   * Only return traces whose {@link Span#duration()} is greater than or equal to minDuration
   * microseconds.
   */
  @Nullable public Long minDuration() {
    return minDuration;
  }

  /**
   * Only return traces whose {@link Span#duration()} is less than or equal to maxDuration
   * microseconds. Only valid with {@link #minDuration}.
   */
  @Nullable public Long maxDuration() {
    return maxDuration;
  }

  /**
   * Only return traces where all {@link Span#timestamp()} are at or before this time in epoch
   * milliseconds. Defaults to current time.
   */
  public long endTs() {
    return endTs;
  }

  /**
   * Only return traces where all {@link Span#timestamp()} are at or after (endTs - lookback) in
   * milliseconds. Defaults to endTs.
   */
  public long lookback() {
    return lookback;
  }

  /** Maximum number of traces to return. Defaults to 10 */
  public int limit() {
    return limit;
  }

  /**
   * Corresponds to query parameter "annotationQuery". Ex. "http.method=GET and error"
   *
   * @see QueryRequest.Builder#parseAnnotationQuery(String)
   */
  @Nullable public String annotationQueryString() {
    StringBuilder result = new StringBuilder();

    for (Iterator<Map.Entry<String, String>> i = annotationQuery().entrySet().iterator();
      i.hasNext(); ) {
      Map.Entry<String, String> next = i.next();
      result.append(next.getKey());
      if (!next.getValue().isEmpty()) result.append('=').append(next.getValue());
      if (i.hasNext()) result.append(" and ");
    }

    return result.length() > 0 ? result.toString() : null;
  }

  public Builder toBuilder() {
    return new Builder(this);
  }

  public static Builder newBuilder() {
    return new Builder();
  }

  public static final class Builder {
    String serviceName, remoteServiceName, spanName;
    Map<String, String> annotationQuery = Collections.emptyMap();
    Long minDuration, maxDuration;
    long endTs, lookback;
    int limit;

    Builder(QueryRequest source) {
      serviceName = source.serviceName;
      remoteServiceName = source.remoteServiceName;
      spanName = source.spanName;
      annotationQuery = source.annotationQuery;
      minDuration = source.minDuration;
      maxDuration = source.maxDuration;
      endTs = source.endTs;
      lookback = source.lookback;
      limit = source.limit;
    }

    /** Sets {@link QueryRequest#serviceName()} */
    public Builder serviceName(@Nullable String serviceName) {
      this.serviceName = serviceName;
      return this;
    }

    /** Sets {@link QueryRequest#remoteServiceName()} */
    public Builder remoteServiceName(@Nullable String remoteServiceName) {
      this.remoteServiceName = remoteServiceName;
      return this;
    }

    /**
     * This ignores the reserved span name "all".
     *
     * @see QueryRequest#spanName()
     */
    public Builder spanName(@Nullable String spanName) {
      this.spanName = spanName;
      return this;
    }

    /**
     * Corresponds to query parameter "annotationQuery". Ex. "http.method=GET and error". Parameter
     * keys and values are trimmed.
     *
     * @see QueryRequest#annotationQueryString()
     */
    public Builder parseAnnotationQuery(@Nullable String annotationQuery) {
      if (annotationQuery == null || annotationQuery.isEmpty()) return this;
      Map<String, String> map = new LinkedHashMap<String, String>();
      for (String ann : annotationQuery.split(" and ", 100)) {
        int idx = ann.indexOf('=');
        if (idx == -1) {
          // put the annotation only if there is no key present already, prevents overriding more specific tags
          ann = ann.trim();
          if (!map.containsKey(ann)) map.put(ann, "");
        } else {
          // tag
          String[] keyValue = ann.split("=", 2);
          // tags are put regardless, i.e. last tag wins
          map.put(ann.substring(0, idx).trim(),
            keyValue.length < 2 ? "" : ann.substring(idx + 1).trim());
        }
      }
      return annotationQuery(map);
    }

    /** Sets {@link QueryRequest#annotationQuery()} */
    public Builder annotationQuery(Map<String, String> annotationQuery) {
      if (annotationQuery == null) throw new NullPointerException("annotationQuery == null");
      this.annotationQuery = annotationQuery;
      return this;
    }

    /** Sets {@link QueryRequest#minDuration()} */
    public Builder minDuration(@Nullable Long minDuration) {
      this.minDuration = minDuration;
      return this;
    }

    /** Sets {@link QueryRequest#maxDuration()} */
    public Builder maxDuration(@Nullable Long maxDuration) {
      this.maxDuration = maxDuration;
      return this;
    }

    /** Sets {@link QueryRequest#endTs()} */
    public Builder endTs(long endTs) {
      this.endTs = endTs;
      return this;
    }

    /** Sets {@link QueryRequest#lookback()} */
    public Builder lookback(long lookback) {
      this.lookback = lookback;
      return this;
    }

    /** Sets {@link QueryRequest#limit()} */
    public Builder limit(int limit) {
      this.limit = limit;
      return this;
    }

    public final QueryRequest build() {
      // coerce service and span names to lowercase
      if (serviceName != null) serviceName = serviceName.toLowerCase(Locale.ROOT);
      if (remoteServiceName != null) remoteServiceName = remoteServiceName.toLowerCase(Locale.ROOT);
      if (spanName != null) spanName = spanName.toLowerCase(Locale.ROOT);

      // remove any accidental empty strings
      annotationQuery.remove("");
      if ("".equals(serviceName)) serviceName = null;
      if ("".equals(remoteServiceName)) remoteServiceName = null;
      if ("".equals(spanName) || "all".equals(spanName)) spanName = null;

      if (endTs <= 0) throw new IllegalArgumentException("endTs <= 0");
      if (limit <= 0) throw new IllegalArgumentException("limit <= 0");
      if (lookback <= 0) throw new IllegalArgumentException("lookback <= 0");
      if (minDuration != null) {
        if (minDuration <= 0) throw new IllegalArgumentException("minDuration <= 0");
        if (maxDuration != null && maxDuration < minDuration) {
          throw new IllegalArgumentException("maxDuration < minDuration");
        }
      } else if (maxDuration != null) {
        throw new IllegalArgumentException("maxDuration is only valid with minDuration");
      }

      return new QueryRequest(
        serviceName,
        remoteServiceName,
        spanName,
        annotationQuery,
        minDuration,
        maxDuration,
        endTs,
        lookback,
        limit
      );
    }

    Builder() {
    }
  }

  /**
   * Tests the supplied trace against the current request.
   *
   * <p>This is used when the backend cannot fully refine a trace query.
   */
  public boolean test(List<Span> spans) {
    // v2 returns raw spans in any order, get the root's timestamp or the first timestamp
    long timestamp = 0L;
    for (Span span : spans) {
      if (span.timestampAsLong() == 0L) continue;
      if (span.parentId() == null) {
        timestamp = span.timestampAsLong();
        break;
      }
      if (timestamp == 0L || timestamp > span.timestampAsLong()) {
        timestamp = span.timestampAsLong();
      }
    }
    if (timestamp == 0L ||
      timestamp < (endTs() - lookback()) * 1000 ||
      timestamp > endTs() * 1000) {
      return false;
    }
    boolean testedDuration = minDuration() == null && maxDuration() == null;

    String serviceNameToMatch = serviceName();
    String remoteServiceNameToMatch = remoteServiceName();
    String spanNameToMatch = spanName();
    Map<String, String> annotationQueryRemaining =
      new LinkedHashMap<String, String>(annotationQuery());

    for (Span span : spans) {
      String localServiceName = span.localServiceName();

      // service name, when present, constrains other queries.
      if (serviceName() == null || serviceName().equals(localServiceName)) {
        serviceNameToMatch = null;
        for (Annotation a : span.annotations()) {
          if ("".equals(annotationQueryRemaining.get(a.value()))) {
            annotationQueryRemaining.remove(a.value());
          }
        }
        for (Map.Entry<String, String> t : span.tags().entrySet()) {
          String value = annotationQueryRemaining.get(t.getKey());
          if (value == null) continue;
          if (value.isEmpty() || value.equals(t.getValue())) {
            annotationQueryRemaining.remove(t.getKey());
          }
        }
        if (remoteServiceNameToMatch != null && remoteServiceNameToMatch.equals(
          span.remoteServiceName())) {
          remoteServiceNameToMatch = null;
        }
        if (spanNameToMatch != null && spanNameToMatch.equals(span.name())) {
          spanNameToMatch = null;
        }
        if (!testedDuration) {
          if (minDuration() != null && maxDuration() != null) {
            testedDuration =
              span.durationAsLong() >= minDuration() && span.durationAsLong() <= maxDuration();
          } else if (minDuration() != null) {
            testedDuration = span.durationAsLong() >= minDuration();
          }
        }
      }
    }
    return (serviceName() == null || serviceNameToMatch == null)
      && remoteServiceNameToMatch == null
      && spanNameToMatch == null
      && annotationQueryRemaining.isEmpty()
      && testedDuration;
  }

  final String serviceName, remoteServiceName, spanName;
  final Map<String, String> annotationQuery;
  final Long minDuration, maxDuration;
  final long endTs, lookback;
  final int limit;

  QueryRequest(
    @Nullable String serviceName,
    @Nullable String remoteServiceName,
    @Nullable String spanName,
    Map<String, String> annotationQuery,
    @Nullable Long minDuration,
    @Nullable Long maxDuration,
    long endTs,
    long lookback,
    int limit) {
    this.serviceName = serviceName;
    this.remoteServiceName = remoteServiceName;
    this.spanName = spanName;
    this.annotationQuery = annotationQuery;
    this.minDuration = minDuration;
    this.maxDuration = maxDuration;
    this.endTs = endTs;
    this.lookback = lookback;
    this.limit = limit;
  }

  @Override public String toString() {
    String result = "QueryRequest{";
    result += ("endTs=" + endTs + ", ");
    result += ("lookback=" + lookback + ", ");
    if (serviceName != null) result += ("serviceName=" + serviceName + ", ");
    if (remoteServiceName != null) result += ("remoteServiceName=" + remoteServiceName + ", ");
    if (spanName != null) result += ("spanName=" + spanName + ", ");
    if (!annotationQuery.isEmpty()) result += ("annotationQuery=" + annotationQuery + ", ");
    if (minDuration != null) result += ("minDuration=" + minDuration + ", ");
    if (maxDuration != null) result += ("maxDuration=" + maxDuration + ", ");
    return result + "limit=" + limit + "}";
  }
}
