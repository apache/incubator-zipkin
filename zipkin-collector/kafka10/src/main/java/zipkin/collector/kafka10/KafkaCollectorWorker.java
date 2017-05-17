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
package zipkin.collector.kafka10;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRebalanceListener;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zipkin.Codec;
import zipkin.collector.Collector;
import zipkin.collector.CollectorMetrics;

import static zipkin.storage.Callback.NOOP;

/** Consumes spans from Kafka messages, ignoring malformed input */
final class KafkaCollectorWorker implements Runnable {
  private static final Logger LOG = LoggerFactory.getLogger(KafkaCollectorWorker.class);

  final Consumer<byte[], byte[]> kafkaConsumer;
  final Collector collector;
  final CollectorMetrics metrics;
  /** Kafka topic partitions currently assigned to this worker. List is not modifiable. */
  final AtomicReference<List<TopicPartition>> assignedPartitions =
      new AtomicReference<>(Collections.emptyList());

  KafkaCollectorWorker(KafkaCollector.Builder builder) {
    kafkaConsumer = new KafkaConsumer<>(builder.properties);
    kafkaConsumer.subscribe(Collections.singleton(builder.topic), new ConsumerRebalanceListener() {
      @Override public void onPartitionsRevoked(Collection<TopicPartition> partitions) {
        assignedPartitions.set(Collections.emptyList());
      }

      @Override public void onPartitionsAssigned(Collection<TopicPartition> partitions) {
        assignedPartitions.set(Collections.unmodifiableList(new ArrayList<>(partitions)));
      }
    });
    this.collector = builder.delegate.build();
    this.metrics = builder.metrics;
  }

  @Override
  public void run() {
    try {
      LOG.info("Kafka consumer starting polling loop.");
      while (true) {
        final ConsumerRecords<byte[], byte[]> consumerRecords = kafkaConsumer.poll(1000);
        LOG.debug("Kafka polling returned batch of {} messages.", consumerRecords.count());
        for (ConsumerRecord<byte[], byte[]> record : consumerRecords) {
          metrics.incrementMessages();
          final byte[] bytes = record.value();

          if (bytes.length == 0) {
            metrics.incrementMessagesDropped();
          } else {
            // In TBinaryProtocol encoding, the first byte is the TType, in a range 0-16
            // .. If the first byte isn't in that range, it isn't a thrift.
            //
            // When byte(0) == '[' (91), assume it is a list of json-encoded spans
            //
            // When byte(0) <= 16, assume it is a TBinaryProtocol-encoded thrift
            // .. When serializing a Span (Struct), the first byte will be the type of a field
            // .. When serializing a List[ThriftSpan], the first byte is the member type, TType.STRUCT(12)
            // .. As ThriftSpan has no STRUCT fields: so, if the first byte is TType.STRUCT(12), it is a list.
            if (bytes[0] == '[') {
              collector.acceptSpans(bytes, Codec.JSON, NOOP);
            } else {
              if (bytes[0] == 12 /* TType.STRUCT */) {
                collector.acceptSpans(bytes, Codec.THRIFT, NOOP);
              } else {
                collector.acceptSpans(Collections.singletonList(bytes), Codec.THRIFT, NOOP);
              }
            }
          }
        }
      }
    } finally {
      LOG.info("Kafka consumer polling loop stopped.");
      LOG.info("Closing Kafka consumer...");
      kafkaConsumer.close();
      LOG.info("Kafka consumer closed.");
    }
  }
}
