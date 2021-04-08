/*
 * Copyright 2015-2021 The OpenZipkin Authors
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
package zipkin2.server.internal;

import com.linecorp.armeria.common.CommonPools;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.grpc.protocol.AbstractUnsafeUnaryGrpcService;
import com.linecorp.armeria.spring.ArmeriaServerConfigurator;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import zipkin2.Callback;
import zipkin2.codec.SpanBytesDecoder;
import zipkin2.collector.Collector;
import zipkin2.collector.CollectorMetrics;
import zipkin2.collector.CollectorSampler;
import zipkin2.storage.StorageComponent;

/** Collector for receiving spans on a gRPC endpoint. */
@ConditionalOnProperty(name = "zipkin.collector.grpc.enabled") // disabled by default
final class ZipkinGrpcCollector {

  @Bean ArmeriaServerConfigurator grpcCollectorConfigurator(StorageComponent storage,
    CollectorSampler sampler, CollectorMetrics metrics) {
    CollectorMetrics grpcMetrics = metrics.forTransport("grpc");
    Collector collector = Collector.newBuilder(getClass())
      .storage(storage)
      .sampler(sampler)
      .metrics(grpcMetrics)
      .build();

    return sb ->
      sb.service("/zipkin.proto3.SpanService/Report", new SpanService(collector, grpcMetrics));
  }

  static final class SpanService extends AbstractUnsafeUnaryGrpcService {

    final Collector collector;
    final CollectorMetrics metrics;

    SpanService(Collector collector, CollectorMetrics metrics) {
      this.collector = collector;
      this.metrics = metrics;
    }

    @Override protected CompletionStage<ByteBuf> handleMessage(ServiceRequestContext ctx, ByteBuf bytes) {
      metrics.incrementMessages();
      metrics.incrementBytes(bytes.readableBytes());

      if (!bytes.isReadable()) {
        return CompletableFuture.completedFuture(bytes); // lenient on empty messages
      }

      try {
        CompletableFutureCallback result = new CompletableFutureCallback();
        collector.acceptSpans(bytes.nioBuffer(), SpanBytesDecoder.PROTO3, result, ctx.blockingTaskExecutor());
        return result;
      } finally {
        bytes.release();
      }
    }
  }

  static final class CompletableFutureCallback extends CompletableFuture<ByteBuf>
    implements Callback<Void> {

    @Override public void onSuccess(Void value) {
      complete(Unpooled.EMPTY_BUFFER);
    }

    @Override public void onError(Throwable t) {
      completeExceptionally(t);
    }
  }
}
