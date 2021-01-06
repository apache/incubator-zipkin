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
package zipkin2.collector.kafka;

import java.time.Duration;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.KafkaFuture;
import org.apache.kafka.common.config.ConfigException;
import org.apache.kafka.common.errors.InterruptException;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zipkin2.Call;
import zipkin2.CheckResult;
import zipkin2.collector.Collector;
import zipkin2.collector.CollectorComponent;
import zipkin2.collector.CollectorMetrics;
import zipkin2.collector.CollectorSampler;
import zipkin2.storage.SpanConsumer;
import zipkin2.storage.StorageComponent;

import static org.apache.kafka.clients.consumer.ConsumerConfig.AUTO_OFFSET_RESET_CONFIG;
import static org.apache.kafka.clients.consumer.ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG;
import static org.apache.kafka.clients.consumer.ConsumerConfig.GROUP_ID_CONFIG;
import static org.apache.kafka.clients.consumer.ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG;
import static org.apache.kafka.clients.consumer.ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG;

/**
 * This collector polls a Kafka topic for messages that contain TBinaryProtocol big-endian encoded
 * lists of spans. These spans are pushed to a {@link SpanConsumer#accept span consumer}.
 *
 * <p>This collector uses a Kafka 0.10+ consumer.
 */
public final class KafkaCollector extends CollectorComponent {
  private static final Logger LOG = LoggerFactory.getLogger(KafkaCollector.class);

  public static Builder builder() {
    return new Builder();
  }

  /** Configuration including defaults needed to consume spans from a Kafka topic. */
  public static final class Builder extends CollectorComponent.Builder {
    final Properties properties = new Properties();
    final Collector.Builder delegate = Collector.newBuilder(KafkaCollector.class);
    CollectorMetrics metrics = CollectorMetrics.NOOP_METRICS;
    String topic = "zipkin";
    int streams = 1;
    Function<Properties, Consumer<byte[], byte[]>> consumerSupplier = KafkaConsumer::new;

    @Override
    public Builder storage(StorageComponent storage) {
      delegate.storage(storage);
      return this;
    }

    @Override
    public Builder sampler(CollectorSampler sampler) {
      delegate.sampler(sampler);
      return this;
    }

    @Override
    public Builder metrics(CollectorMetrics metrics) {
      if (metrics == null) throw new NullPointerException("metrics == null");
      this.metrics = metrics.forTransport("kafka");
      delegate.metrics(this.metrics);
      return this;
    }

    /**
     * Topic zipkin spans will be consumed from. Defaults to "zipkin". Multiple topics may be
     * specified if comma delimited.
     */
    public Builder topic(String topic) {
      if (topic == null) throw new NullPointerException("topic == null");
      this.topic = topic;
      return this;
    }

    /** The bootstrapServers connect string, ex. 127.0.0.1:9092. No default. */
    public Builder bootstrapServers(String bootstrapServers) {
      if (bootstrapServers == null) throw new NullPointerException("bootstrapServers == null");
      properties.put(BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
      return this;
    }

    /** The consumer group this process is consuming on behalf of. Defaults to "zipkin" */
    public Builder groupId(String groupId) {
      if (groupId == null) throw new NullPointerException("groupId == null");
      properties.put(GROUP_ID_CONFIG, groupId);
      return this;
    }

    /** Count of threads consuming the topic. Defaults to 1 */
    public Builder streams(int streams) {
      this.streams = streams;
      return this;
    }

    public Builder consumerSupplier(Function<Properties, Consumer<byte[], byte[]>> consumerSupplier) {
      this.consumerSupplier = consumerSupplier;
      return this;
    }

    /**
     * By default, a consumer will be built from properties derived from builder defaults, as well
     * as "auto.offset.reset" -> "earliest". Any properties set here will override the consumer
     * config.
     *
     * <p>For example: Only consume spans since you connected by setting the below.
     *
     * <pre>{@code
     * Map<String, String> overrides = new LinkedHashMap<>();
     * overrides.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
     * builder.overrides(overrides);
     * }</pre>
     *
     * @see org.apache.kafka.clients.consumer.ConsumerConfig
     */
    public final Builder overrides(Map<String, ?> overrides) {
      if (overrides == null) throw new NullPointerException("overrides == null");
      properties.putAll(overrides);
      return this;
    }

    @Override
    public KafkaCollector build() {
      return new KafkaCollector(this);
    }

    Builder() {
      // Settings below correspond to "New Consumer Configs"
      // https://kafka.apache.org/documentation/#newconsumerconfigs
      properties.put(GROUP_ID_CONFIG, "zipkin");
      properties.put(AUTO_OFFSET_RESET_CONFIG, "earliest");
      properties.put(KEY_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
      properties.put(VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
    }
  }

  final LazyKafkaWorkers kafkaWorkers;
  final Properties properties;
  volatile AdminClient adminClient;

  KafkaCollector(Builder builder) {
    kafkaWorkers = new LazyKafkaWorkers(builder);
    properties = builder.properties;
  }

  @Override
  public KafkaCollector start() {
    kafkaWorkers.start();
    return this;
  }

  @Override
  public CheckResult check() {
    try {
      CheckResult failure = kafkaWorkers.failure.get(); // check the kafka workers didn't quit
      if (failure != null) return failure;
      KafkaFuture<String> maybeClusterId = getAdminClient().describeCluster().clusterId();
      maybeClusterId.get(1, TimeUnit.SECONDS);
      return CheckResult.OK;
    } catch (Throwable e) {
      Call.propagateIfFatal(e);
      return CheckResult.failed(e);
    }
  }

  AdminClient getAdminClient() {
    if (adminClient == null) {
      synchronized (this) {
        if (adminClient == null) {
          adminClient = AdminClient.create(properties);
        }
      }
    }
    return adminClient;
  }

  @Override
  public void close() {
    kafkaWorkers.close();
    if (adminClient != null) adminClient.close(Duration.ofSeconds(1));
  }

  @Override public final String toString() {
    return "KafkaCollector{"
      + "bootstrapServers=" + properties.get(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG)
      + ", topic=" + kafkaWorkers.builder.topic
      + "}";
  }

  static final class LazyKafkaWorkers {
    final int streams;
    final Builder builder;
    final AtomicReference<CheckResult> failure = new AtomicReference<>();
    final CopyOnWriteArrayList<KafkaCollectorWorker> workers = new CopyOnWriteArrayList<>();
    volatile ExecutorService pool;

    LazyKafkaWorkers(Builder builder) {
      this.streams = builder.streams;
      this.builder = builder;
    }

    void start() {
      if (pool == null) {
        synchronized (this) {
          if (pool == null) {
            pool = compute();
          }
        }
      }
    }

    void close() {
      ExecutorService maybePool = pool;
      if (maybePool == null) return;
      for (KafkaCollectorWorker worker : workers) {
        worker.stop();
      }
      maybePool.shutdown();
      try {
        if (!maybePool.awaitTermination(2, TimeUnit.SECONDS)) {
          // Timeout exceeded: force shutdown
          maybePool.shutdownNow();
        }
      } catch (InterruptedException e) {
        // at least we tried
      }
    }

    ExecutorService compute() {
      ExecutorService pool =
        streams == 1 ? Executors.newSingleThreadExecutor() : Executors.newFixedThreadPool(streams);

      for (int i = 0; i < streams; i++) {
        // TODO: bad idea to lazy reference properties from a mutable builder
        // copy them here and then pass this to the KafkaCollectorWorker constructor instead
        KafkaCollectorWorker worker = new KafkaCollectorWorker(builder);
        workers.add(worker);
        pool.execute(guardFailures(worker));
      }

      return pool;
    }

    Runnable guardFailures(final Runnable delegate) {
      return () -> {
        try {
          delegate.run();
        } catch (InterruptException e) {
          // Interrupts are normal on shutdown, intentionally swallow
        } catch (KafkaException e) {
          if (e.getCause() instanceof ConfigException) e = (KafkaException) e.getCause();
          LOG.error("Kafka worker exited with exception", e);
          failure.set(CheckResult.failed(e));
        } catch (RuntimeException e) {
          LOG.error("Kafka worker exited with exception", e);
          failure.set(CheckResult.failed(e));
        } catch (Error e) {
          LOG.error("Kafka worker exited with error", e);
          failure.set(CheckResult.failed(new RuntimeException(e)));
        }
      };
    }
  }
}
