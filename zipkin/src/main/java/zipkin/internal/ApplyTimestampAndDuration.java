/**
 * Copyright 2015-2016 The OpenZipkin Authors
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
package zipkin.internal;

import zipkin.Annotation;
import zipkin.Constants;
import zipkin.Span;

/**
 * <h3>Derived timestamp and duration</h3>
 *
 * <p>Instrumentation should log timestamp and duration, but since these fields are recent
 * (Nov-2015), a lot of tracers will not. Accordingly, this will backfill timestamp and duration to
 * if possible, based on interpretation of annotations.
 */
public class ApplyTimestampAndDuration {

  // For spans that core client annotations, the distance between "cs" and "cr" should be the
  // authoritative duration. We are special-casing this to avoid setting incorrect duration
  // when there's skew between the client and the server.
  public static Span apply(Span span) {
    // Don't overwrite authoritatively set timestamp and duration!
    if (span.timestamp != null && span.duration != null) {
      return span;
    }

    // Only calculate span.timestamp and duration on complete spans. This avoids
    // persisting an inaccurate timestamp due to a late arriving annotation.
    if (span.annotations.size() < 2) {
      return span;
    }

    // For spans that core client annotations, the distance between "cs" and "cr" should be the
    // authoritative duration. We are special-casing this to avoid setting incorrect duration
    // when there's skew between the client and the server.
    Long first = span.annotations.get(0).timestamp;
    Long last = span.annotations.get(span.annotations.size() - 1).timestamp;
    for (int i = 0, length = span.annotations.size(); i < length; i++) {
      Annotation annotation = span.annotations.get(i);
      if (annotation.value.equals(Constants.CLIENT_SEND)) {
        first = annotation.timestamp;
      } else if (annotation.value.equals(Constants.CLIENT_RECV)) {
        last = annotation.timestamp;
      }
    }
    long ts = span.timestamp != null ? span.timestamp : first;
    Long dur = span.duration != null ? span.duration : last.equals(first) ? null : last - first;
    return span.toBuilder().timestamp(ts).duration(dur).build();
  }

  /**
   * Instrumentation should set {@link Span#timestamp} when recording a span so that guess-work
   * isn't needed. Since a lot of instrumentation don't, we have to make some guesses.
   *
   * <pre><ul>
   *   <li>If there is a {@link Constants#CLIENT_SEND}, use that</li>
   *   <li>Fall back to {@link Constants#SERVER_RECV}, if a root span</li>
   *   <li>Otherwise, return null</li>
   * </ul></pre>
   */
  public static Long guessTimestamp(Span span) {
    if (span.timestamp != null || span.annotations.isEmpty()) return span.timestamp;
    boolean isRoot = span.parentId == null;
    Long rootServerRecv = null;
    for (int i = 0, length = span.annotations.size(); i < length; i++) {
      Annotation annotation = span.annotations.get(i);
      if (annotation.value.equals(Constants.CLIENT_SEND)) {
        return annotation.timestamp;
      } else if (annotation.value.equals(Constants.SERVER_RECV) && isRoot) {
        rootServerRecv = annotation.timestamp;
      }
    }
    return rootServerRecv;
  }

  private ApplyTimestampAndDuration() {
  }
}
