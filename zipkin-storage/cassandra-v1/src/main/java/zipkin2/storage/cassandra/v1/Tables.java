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

import zipkin2.Span;
import zipkin2.internal.Dependencies;
import zipkin2.internal.V1ThriftSpanWriter;
import zipkin2.storage.AutocompleteTags;
import zipkin2.storage.QueryRequest;
import zipkin2.storage.ServiceAndSpanNames;

final class Tables {
  /**
   * This table includes {@linkplain V1ThriftSpanWriter thrift-encoded} spans, supporting {@link
   * SelectFromTraces}.
   */
  static final String TRACES = "traces";

  /**
   * This table includes {@linkplain Dependencies#fromThrift thrift-encoded} dependency links,
   * supporting {@link SelectDependencies}.
   */
  static final String DEPENDENCIES = "dependencies";

  /**
   * This index supports {@link ServiceAndSpanNames#getServiceNames()}}.
   *
   * <p>The cardinality of {@link Span#localServiceName()} values is expected to be stable and low.
   * There may be hot partitions, but each partition only includes a single column. Hence bucketing
   * would be unnecessary.
   */
  static final String SERVICE_NAMES = "service_names";

  /**
   * This index supports {@link ServiceAndSpanNames#getRemoteServiceNames(String)}.
   *
   * <p>The cardinality of {@link Span#remoteServiceName()} values is expected to be stable and
   * low. There may be hot partitions, but each partition only includes a single column. Hence
   * bucketing would be unnecessary.
   */
  static final String REMOTE_SERVICE_NAMES = "remote_service_names";

  /**
   * This index supports {@link ServiceAndSpanNames#getSpanNames(String)}.
   *
   * <p>The compound partition key includes {@link Span#localServiceName()} and a constant bucket
   * (0). This is because span names are bounded (and will throw an exception if aren't!).
   */
  static final String SPAN_NAMES = "span_names";

  /** This table supports {@link AutocompleteTags#getValues(String key)}. */
  static final String AUTOCOMPLETE_TAGS = "autocomplete_tags";

  /**
   * This index supports trace id lookups by {@link QueryRequest#serviceName()}, within the interval
   * of {@link QueryRequest#endTs()} - {@link QueryRequest#lookback()}.
   *
   * <p>The cardinality of  {@link Span#localServiceName()} values is expected to be stable and
   * low. To avoid hot partitions, the partition key is {@link Span#localServiceName()}  with a
   * bucket (random number between 0 and 9).
   */
  static final String SERVICE_NAME_INDEX = "service_name_index";

  /**
   * This index supports trace id lookups by {@link QueryRequest#serviceName()} and {@link
   * QueryRequest#remoteServiceName()}, within the interval of {@link QueryRequest#endTs()} - {@link
   * QueryRequest#lookback()}.
   *
   * <p>The partition key is "{@link Span#localServiceName() $serviceName}.{@link
   * Span#remoteServiceName() $remoteServiceName}", which is expected to be diverse enough to not
   * cause hot partitions.
   */
  static final String SERVICE_REMOTE_SERVICE_NAME_INDEX = "service_remote_service_name_index";

  /**
   * This index supports trace id lookups by {@link QueryRequest#serviceName()} and {@link
   * QueryRequest#spanName()}, within the interval of {@link QueryRequest#endTs()} - {@link
   * QueryRequest#lookback()}.
   *
   * <p>The partition key is "{@link Span#localServiceName() $serviceName}.{@link
   * Span#name() $spanName}", which is expected to be diverse enough to not cause hot partitions.
   */
  static final String SERVICE_SPAN_NAME_INDEX = "service_span_name_index";

  /**
   * This index supports trace id lookups by {@link QueryRequest#annotationQuery()}, within the
   * interval of {@link QueryRequest#endTs()} - {@link QueryRequest#lookback()}.
   *
   * <p>The annotation field is a colon-delimited string beginning with {@link
   * Span#localServiceName()}. If an {@link zipkin2.Annotation}, the second part will be the value.
   * If a {@link Span#tags() tag}, the second part would be the key, and the third, the value.
   *
   * <p>For example, an annotation of "error" logged by "backend2" would be stored as the
   * annotation field "backend2:error". A tag of "http.method" -> "GET", logged by "edge1" would
   * result in two rows: one with annotation field "edge1:http.method" and another with
   * "edge1:http.method:GET".
   *
   * <p>To keep the size of this index reasonable, {@link CassandraUtil#CORE_ANNOTATIONS} are not
   * indexed. For example, "service:sr" won't be stored, as it isn't supported to search by core
   * annotations. Also, tag values longer than 256 characters are not indexed.
   *
   * <p>Lookups are by equals (not partial match), so it is expected that {@link
   * zipkin2.Annotation} and {@link Span#tags() tag} keys and values will be low or bounded
   * cardinality. To avoid hot partitions, the partition key is the annotation field with a bucket
   * (random number between 0 and 9).
   */
  static final String ANNOTATIONS_INDEX = "annotations_index";

  private Tables() {
  }
}
