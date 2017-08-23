/**
 * Copyright 2015-2017 The OpenZipkin Authors
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
package zipkin.internal.v2;

import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import okio.Buffer;
import org.junit.Test;
import zipkin.Annotation;
import zipkin.internal.Util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.MapEntry.entry;
import static zipkin.TestObjects.APP_ENDPOINT;

public class SpanTest {
  Span base = Span.builder().traceId(1L).id(1L).localEndpoint(APP_ENDPOINT).build();

  @Test public void traceIdString() {
    Span with128BitId = Span.builder()
      .traceId(Util.lowerHexToUnsignedLong("48485a3953bb6124"))
      .id(1)
      .name("foo").build();

    assertThat(with128BitId.traceIdString())
      .isEqualTo("48485a3953bb6124");
  }

  @Test public void traceIdString_high() {
    Span with128BitId = Span.builder()
      .traceId(Util.lowerHexToUnsignedLong("48485a3953bb6124"))
      .traceIdHigh(Util.lowerHexToUnsignedLong("463ac35c9f6413ad"))
      .id(1)
      .name("foo").build();

    assertThat(with128BitId.traceIdString())
      .isEqualTo("463ac35c9f6413ad48485a3953bb6124");
  }

  @Test
  public void idString_traceIdHigh() {
    Span with128BitId = Span.builder()
      .traceId(Util.lowerHexToUnsignedLong("48485a3953bb6124"))
      .traceIdHigh(Util.lowerHexToUnsignedLong("463ac35c9f6413ad"))
      .id(1)
      .name("foo").build();

    assertThat(with128BitId.idString())
      .isEqualTo("463ac35c9f6413ad48485a3953bb6124.0000000000000001<:0000000000000001");
  }

  @Test
  public void idString_withParent() {
    Span withParent = Span.builder().name("foo").traceId(1).id(3).parentId(2L).build();

    assertThat(withParent.idString())
      .isEqualTo("0000000000000001.0000000000000003<:0000000000000002");
  }

  @Test
  public void idString_noParent() {
    Span noParent = Span.builder().name("foo").traceId(1).id(1).build();

    assertThat(noParent.idString())
      .isEqualTo("0000000000000001.0000000000000001<:0000000000000001");
  }

  @Test public void spanNamesLowercase() {
    assertThat(base.toBuilder().name("GET").build().name())
      .isEqualTo("get");
  }

  @Test public void annotationsSortByTimestamp() {
    Span span = base.toBuilder()
      .addAnnotation(2L, "foo")
      .addAnnotation(1L, "foo")
      .build();

    // note: annotations don't also have endpoints, as it is implicit to Span.localEndpoint
    assertThat(span.annotations()).containsExactly(
      Annotation.create(1L, "foo", null),
      Annotation.create(2L, "foo", null)
    );
  }

  @Test public void putTagOverwritesValue() {
    Span span = base.toBuilder()
      .putTag("foo", "bar")
      .putTag("foo", "qux")
      .build();

    assertThat(span.tags()).containsExactly(
      entry("foo", "qux")
    );
  }

  @Test public void clone_differentCollections() {
    Span.Builder builder = base.toBuilder()
      .addAnnotation(1L, "foo")
      .putTag("foo", "qux");

    Span.Builder builder2 = builder.clone()
      .addAnnotation(2L, "foo")
      .putTag("foo", "bar");

    assertThat(builder.build()).isEqualTo(base.toBuilder()
      .addAnnotation(1L, "foo")
      .putTag("foo", "qux")
      .build()
    );

    assertThat(builder2.build()).isEqualTo(base.toBuilder()
      .addAnnotation(1L, "foo")
      .addAnnotation(2L, "foo")
      .putTag("foo", "bar")
      .build()
    );
  }

  /** Catches common error when zero is passed instead of null for a timestamp */
  @Test public void coercesZeroTimestampsToNull() {
    Span span = base.toBuilder()
      .timestamp(0L)
      .duration(0L)
      .build();

    assertThat(span.timestamp())
      .isNull();
    assertThat(span.duration())
      .isNull();
  }

  @Test public void toString_isJson() {
    assertThat(base.toString()).hasToString(
      "{\"traceId\":\"0000000000000001\",\"id\":\"0000000000000001\",\"localEndpoint\":{\"serviceName\":\"app\",\"ipv4\":\"172.17.0.2\",\"port\":8080}}"
    );
  }

  /** Test serializable as used in spark jobs. Careful to include all non-standard fields */
  @Test public void serialization() throws Exception {
    Buffer buffer = new Buffer();

    Span span = base.toBuilder()
      .addAnnotation(1L, "foo")
      .build();

    new ObjectOutputStream(buffer.outputStream()).writeObject(span);

    assertThat(new ObjectInputStream(buffer.inputStream()).readObject())
      .isEqualTo(span);
  }
}
