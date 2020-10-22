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

import com.datastax.oss.driver.api.core.ProtocolVersion;
import com.datastax.oss.driver.api.core.cql.Row;
import com.datastax.oss.driver.api.core.type.codec.TypeCodecs;
import java.nio.ByteBuffer;

final class TimestampCodec {
  // This is not read at runtime when deserializing bigint
  static final ProtocolVersion PROTOCOL_VERSION = ProtocolVersion.V4;

  /**
   * Truncates timestamp to milliseconds and converts to binary for using with setBytesUnsafe() to
   * avoid allocating java.util.Date
   */
  static ByteBuffer serialize(long timestamp) {
    return TypeCodecs.BIGINT.encodePrimitive(timestamp / 1000L, PROTOCOL_VERSION);
  }

  /**
   * Reads timestamp binary value directly (getBytesUnsafe) to avoid allocating java.util.Date, and
   * converts to microseconds.
   */
  static long deserialize(Row row, int i) {
    return 1000L * TypeCodecs.BIGINT.decodePrimitive(row.getBytesUnsafe(i), PROTOCOL_VERSION);
  }
}
