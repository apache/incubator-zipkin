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

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.AsyncResultSet;
import com.datastax.oss.driver.api.core.cql.PreparedStatement;
import com.google.auto.value.AutoValue;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletionStage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zipkin2.Call;
import zipkin2.storage.cassandra.internal.call.ResultSetFutureCall;
import zipkin2.v1.V1Span;

import static zipkin2.storage.cassandra.v1.Tables.TRACES;

final class InsertTrace extends ResultSetFutureCall<Void> {
  private static final Logger LOG = LoggerFactory.getLogger(InsertTrace.class);

  @AutoValue abstract static class Input {
    abstract long trace_id();

    abstract long ts();

    abstract String span_name();

    abstract ByteBuffer span();
  }

  static final class Factory {
    final CqlSession session;
    final PreparedStatement preparedStatement;
    final boolean dateTieredCompactionStrategy;

    Factory(CqlSession session, Schema.Metadata metadata, int spanTtl) {
      this.session = session;
      this.dateTieredCompactionStrategy =
        metadata.compactionClass.contains("DateTieredCompactionStrategy");
      String statement = "INSERT INTO " + TRACES + " (trace_id,ts,span_name,span) VALUES (?,?,?,?)";
      this.preparedStatement =
        session.prepare(spanTtl > 0 ? statement + " USING TTL " + spanTtl : statement);
    }

    Input newInput(V1Span v1, ByteBuffer v1Bytes, long ts_micro) {
      String span_name =
        String.format(
          "%s%d_%d_%d",
          v1.traceIdHigh() == 0 ? "" : v1.traceIdHigh() + "_",
          v1.id(),
          v1.annotations().hashCode(),
          v1.binaryAnnotations().hashCode());

      // If we couldn't guess the timestamp, that probably means that there was a missing timestamp.
      if (0L == ts_micro && dateTieredCompactionStrategy) {
        LOG.warn(
          "Span {} in trace {} had no timestamp. "
            + "If this happens a lot consider switching back to SizeTieredCompactionStrategy for "
            + "{}.{}",
          span_name,
          v1.traceId(),
          session.getKeyspace().get());
      }

      return new AutoValue_InsertTrace_Input(v1.traceId(), ts_micro, span_name, v1Bytes);
    }

    Call<Void> create(Input span) {
      return new InsertTrace(this, span);
    }
  }

  final Factory factory;
  final Input input;

  InsertTrace(Factory factory, Input input) {
    this.factory = factory;
    this.input = input;
  }

  @Override protected CompletionStage<AsyncResultSet> newCompletionStage() {
    return factory.session.executeAsync(factory.preparedStatement.boundStatementBuilder()
      .setLong(0, input.trace_id())
      .setBytesUnsafe(1, TimestampCodec.serialize(input.ts()))
      .setString(2, input.span_name())
      .setBytesUnsafe(3, input.span()).build());
  }

  @Override public Void map(AsyncResultSet input) {
    return null;
  }

  @Override public String toString() {
    return input.toString().replace("Input", "InsertTrace");
  }

  @Override public InsertTrace clone() {
    return new InsertTrace(factory, input);
  }
}
