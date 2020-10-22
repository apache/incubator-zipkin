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
package zipkin2.storage.cassandra;

import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import zipkin2.Span;
import zipkin2.TestObjects;
import zipkin2.internal.DateUtil;
import zipkin2.storage.QueryRequest;

import static java.util.concurrent.TimeUnit.DAYS;
import static org.assertj.core.api.Assertions.assertThat;
import static zipkin2.TestObjects.TODAY;

public class CassandraUtilTest {
  @Test public void annotationKeys_emptyRequest() {
    QueryRequest request = QueryRequest.newBuilder()
      .endTs(System.currentTimeMillis())
      .limit(10)
      .serviceName("test")
      .lookback(86400000L)
      .build();

    assertThat(CassandraUtil.annotationKeys(request))
      .isEmpty();
  }

  @Test public void annotationKeys() {
    QueryRequest request = QueryRequest.newBuilder()
      .endTs(System.currentTimeMillis())
      .limit(10)
      .lookback(86400000L)
      .serviceName("service")
      .parseAnnotationQuery("error and http.method=GET")
      .build();

    assertThat(CassandraUtil.annotationKeys(request))
      .containsExactly("error", "http.method=GET");
  }

  @Test public void annotationKeys_dedupes() {
    QueryRequest request = QueryRequest.newBuilder()
      .endTs(System.currentTimeMillis())
      .limit(10)
      .lookback(86400000L)
      .serviceName("service")
      .parseAnnotationQuery("error and error")
      .build();

    assertThat(CassandraUtil.annotationKeys(request))
      .containsExactly("error");
  }

  @Test public void annotationKeys_skipsTagsLongerThan256chars() {
    // example long value
    String arn =
      "arn:aws:acm:us-east-1:123456789012:certificate/12345678-1234-1234-1234-123456789012";
    // example too long value
    String url =
      "http://webservices.amazon.com/onca/xml?AWSAccessKeyId=AKIAIOSFODNN7EXAMPLE&AssociateTag=mytag-20&ItemId=0679722769&Operation=ItemLookup&ResponseGroup=Images%2CItemAttributes%2COffers%2CReviews&Service=AWSECommerceService&Timestamp=2014-08-18T12%3A00%3A00Z&Version=2013-08-01&Signature=j7bZM0LXZ9eXeZruTqWm2DIvDYVUU3wxPPpp%2BiXxzQc%3D";

    Span span =
      TestObjects.CLIENT_SPAN.toBuilder().putTag("aws.arn", arn).putTag("http.url", url).build();

    assertThat(CassandraUtil.annotationQuery(span))
      .contains("aws.arn", "aws.arn=" + arn)
      .doesNotContain("http.url")
      .doesNotContain("http.url=" + url);
  }

  @Test public void annotationKeys_skipsAnnotationsLongerThan256chars() {
    // example long value
    String arn =
      "arn:aws:acm:us-east-1:123456789012:certificate/12345678-1234-1234-1234-123456789012";
    // example too long value
    String url =
      "http://webservices.amazon.com/onca/xml?AWSAccessKeyId=AKIAIOSFODNN7EXAMPLE&AssociateTag=mytag-20&ItemId=0679722769&Operation=ItemLookup&ResponseGroup=Images%2CItemAttributes%2COffers%2CReviews&Service=AWSECommerceService&Timestamp=2014-08-18T12%3A00%3A00Z&Version=2013-08-01&Signature=j7bZM0LXZ9eXeZruTqWm2DIvDYVUU3wxPPpp%2BiXxzQc%3D";

    Span span =
      TestObjects.CLIENT_SPAN.toBuilder().addAnnotation(1L, arn).addAnnotation(1L, url).build();

    assertThat(CassandraUtil.annotationQuery(span)).contains(arn).doesNotContain(url);
  }

  @Test public void annotationKeys_skipsAllocationWhenNoValidInput() {
    // example too long value
    String url =
      "http://webservices.amazon.com/onca/xml?AWSAccessKeyId=AKIAIOSFODNN7EXAMPLE&AssociateTag=mytag-20&ItemId=0679722769&Operation=ItemLookup&ResponseGroup=Images%2CItemAttributes%2COffers%2CReviews&Service=AWSECommerceService&Timestamp=2014-08-18T12%3A00%3A00Z&Version=2013-08-01&Signature=j7bZM0LXZ9eXeZruTqWm2DIvDYVUU3wxPPpp%2BiXxzQc%3D";

    Span span = Span.newBuilder().traceId("1").id("1").build();

    assertThat(CassandraUtil.annotationQuery(span)).isNull();

    span = span.toBuilder().addAnnotation(1L, url).putTag("http.url", url).build();

    assertThat(CassandraUtil.annotationQuery(span)).isNull();
  }

  /** Sanity checks our bucketing scheme for numeric overflow */
  @Test public void durationIndexBucket_notNegative() {
    // today isn't negative
    assertThat(CassandraUtil.durationIndexBucket(TODAY * 1000L)).isNotNegative();
    // neither is 10 years from now
    assertThat(CassandraUtil.durationIndexBucket((TODAY + TimeUnit.DAYS.toMillis(3654)) * 1000L))
      .isNotNegative();
  }

  @Test public void traceIdsSortedByDescTimestamp_doesntCollideOnSameTimestamp() {
    Map<String, Long> input = new LinkedHashMap<>();
    input.put("a", 1L);
    input.put("b", 1L);
    input.put("c", 2L);

    Set<String> sortedTraceIds = CassandraUtil.traceIdsSortedByDescTimestamp().map(input);

    try {
      assertThat(sortedTraceIds).containsExactly("c", "b", "a");
    } catch (AssertionError e) {
      assertThat(sortedTraceIds).containsExactly("c", "a", "b");
    }
  }

  @Test public void getDays_consistentWithDateUtil() {
    assertThat(CassandraUtil.getDays(DAYS.toMillis(2), DAYS.toMillis(1)))
      .extracting(d -> d.atStartOfDay().toEpochSecond(ZoneOffset.UTC) * 1000)
      .containsExactlyElementsOf(DateUtil.epochDays(DAYS.toMillis(2), DAYS.toMillis(1)));
  }
}
