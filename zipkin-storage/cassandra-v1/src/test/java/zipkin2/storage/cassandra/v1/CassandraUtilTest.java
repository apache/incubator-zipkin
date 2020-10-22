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

import java.time.Instant;
import org.junit.Test;
import zipkin2.Span;
import zipkin2.storage.QueryRequest;

import static java.util.concurrent.TimeUnit.DAYS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static zipkin2.TestObjects.CLIENT_SPAN;
import static zipkin2.TestObjects.FRONTEND;

public class CassandraUtilTest {
  QueryRequest request = QueryRequest.newBuilder().endTs(1).limit(1).lookback(1).build();

  @Test public void annotationKeys_emptyRequest() {
    assertThat(CassandraUtil.annotationKeys(request)).isEmpty();
  }

  @Test public void annotationKeys_serviceNameRequired() {
    request = request.toBuilder().parseAnnotationQuery("error").build();

    assertThatThrownBy(() -> CassandraUtil.annotationKeys(request))
      .isInstanceOf(IllegalArgumentException.class);
  }

  @Test public void annotationKeys() {
    request = request.toBuilder()
      .serviceName("service")
      .parseAnnotationQuery("error and http.method=GET")
      .build();

    assertThat(CassandraUtil.annotationKeys(request))
      .containsExactly("service:error", "service:http.method:GET");
  }

  @Test public void annotationKeys_dedupes() {
    request = request.toBuilder()
      .serviceName("service")
      .parseAnnotationQuery("error and error")
      .build();

    assertThat(CassandraUtil.annotationKeys(request))
      .containsExactly("service:error");
  }

  @Test public void annotationKeys_skipsCoreAndAddressAnnotations() {
    // pretend redundant data was added to the span.
    Span span = CLIENT_SPAN.toBuilder()
      .addAnnotation(CLIENT_SPAN.timestampAsLong(), "cs").build();
    assertThat(CassandraUtil.annotationKeys(span)).containsExactly(
      "frontend:foo",
      "frontend:clnt/finagle.version",
      "frontend:clnt/finagle.version:6.45.0",
      "frontend:http.path",
      "frontend:http.path:/api");
  }

  @Test public void annotationKeys_skipsTagsLongerThan256chars() {
    // example long value
    String arn =
      "arn:aws:acm:us-east-1:123456789012:certificate/12345678-1234-1234-1234-123456789012";
    // example too long value
    String url =
      "http://webservices.amazon.com/onca/xml?AWSAccessKeyId=AKIAIOSFODNN7EXAMPLE&AssociateTag=mytag-20&ItemId=0679722769&Operation=ItemLookup&ResponseGroup=Images%2CItemAttributes%2COffers%2CReviews&Service=AWSECommerceService&Timestamp=2014-08-18T12%3A00%3A00Z&Version=2013-08-01&Signature=j7bZM0LXZ9eXeZruTqWm2DIvDYVUU3wxPPpp%2BiXxzQc%3D";
    Span span = Span.newBuilder()
      .traceId("1")
      .id("1")
      .localEndpoint(FRONTEND)
      .putTag("aws.arn", arn)
      .putTag("http.url", url)
      .build();

    assertThat(CassandraUtil.annotationKeys(span))
      .containsOnly("frontend:aws.arn", "frontend:aws.arn:" + arn);
  }

  @Test public void getDays_consistentWithEpochMilli() {
    assertThat(CassandraUtil.getDays(DAYS.toMillis(2), DAYS.toMillis(1)))
      .extracting(Instant::toEpochMilli)
      .containsExactly(DAYS.toMillis(1), DAYS.toMillis(2));
  }
}
