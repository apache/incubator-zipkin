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
package zipkin.storage.elasticsearch;

import com.google.common.base.Function;
import com.google.common.base.Functions;
import com.google.common.util.concurrent.AsyncFunction;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.elasticsearch.action.bulk.BulkRequestBuilder;
import org.elasticsearch.action.index.IndexRequestBuilder;
import org.elasticsearch.client.Client;
import zipkin.Codec;
import zipkin.Span;
import zipkin.internal.ApplyTimestampAndDuration;
import zipkin.storage.guava.GuavaSpanConsumer;

import static com.google.common.util.concurrent.Futures.transform;
import static zipkin.storage.elasticsearch.ElasticFutures.toGuava;

final class ElasticsearchSpanConsumer implements GuavaSpanConsumer {
  private static final Function<Object, Void> TO_VOID = Functions.<Void>constant(null);

  private final Client client;
  private final IndexNameFormatter indexNameFormatter;

  ElasticsearchSpanConsumer(Client client, IndexNameFormatter indexNameFormatter) {
    this.client = client;
    this.indexNameFormatter = indexNameFormatter;
  }

  @Override
  public ListenableFuture<Void> accept(List<Span> spans) {
    if (spans.isEmpty()) return Futures.immediateFuture(null);

    // Create a bulk request when there is more than one span to store
    ListenableFuture<?> future;
    if (spans.size() == 1) {
      future = toGuava(createSpanIndexRequest(spans.get(0)).execute());
    } else {
      BulkRequestBuilder request = client.prepareBulk();
      for (Span span : spans) {
        request.add(createSpanIndexRequest(span));
      }
      future = toGuava(request.execute());
    }

    if (ElasticsearchStorage.FLUSH_ON_WRITES) {
      future = transform(future, new AsyncFunction() {
        @Override public ListenableFuture apply(Object input) {
          return toGuava(client.admin().indices()
              .prepareFlush(indexNameFormatter.catchAll())
              .execute());
        }
      });
    }

    return transform(future, TO_VOID);
  }

  private IndexRequestBuilder createSpanIndexRequest(Span input) {
    Span span = ApplyTimestampAndDuration.apply(input);
    long indexTimestampMillis;
    if (span.timestamp != null) {
      indexTimestampMillis = TimeUnit.MICROSECONDS.toMillis(span.timestamp);
    } else {
      indexTimestampMillis = System.currentTimeMillis();
    }
    String spanIndex = indexNameFormatter.indexNameForTimestamp(indexTimestampMillis);
    return client.prepareIndex(spanIndex, ElasticsearchConstants.SPAN)
        .setSource(Codec.JSON.writeSpan(span));
  }
}
