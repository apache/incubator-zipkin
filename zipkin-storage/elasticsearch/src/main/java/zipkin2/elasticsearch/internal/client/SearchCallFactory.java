/*
 * Copyright 2015-2019 The OpenZipkin Authors
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
package zipkin2.elasticsearch.internal.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.linecorp.armeria.common.AggregatedHttpRequest;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RequestHeaders;
import java.util.List;
import zipkin2.internal.Nullable;

import static zipkin2.elasticsearch.internal.JsonSerializers.OBJECT_MAPPER;

public class SearchCallFactory {
  final HttpCall.Factory http;

  public SearchCallFactory(HttpCall.Factory http) {
    this.http = http;
  }

  public <V> HttpCall<V> newCall(SearchRequest request, HttpCall.BodyConverter<V> bodyConverter) {
    final AggregatedHttpRequest httpRequest;
    try {
      httpRequest = AggregatedHttpRequest.of(
        RequestHeaders.of(
          HttpMethod.POST, lenientSearch(request.indices, request.type),
          HttpHeaderNames.CONTENT_TYPE, MediaType.JSON_UTF_8),
        HttpData.wrap(OBJECT_MAPPER.writeValueAsBytes(request)));
    } catch (JsonProcessingException e) {
      throw new AssertionError("Could not serialize SearchRequest to bytes.", e);
    }
    return http.newCall(httpRequest, bodyConverter, request.tag());
  }

  /** Matches the behavior of {@code IndicesOptions#lenientExpandOpen()} */
  String lenientSearch(List<String> indices, @Nullable String type) {
    return '/' + String.join(",", indices) +
      "/_search?allow_no_indices=true&expand_wildcards=open&ignore_unavailable=true";
  }
}
