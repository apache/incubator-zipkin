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

import com.datastax.oss.driver.api.core.data.UdtValue;
import com.datastax.oss.driver.api.core.type.UserDefinedType;
import com.datastax.oss.driver.api.core.type.codec.MappingCodec;
import com.datastax.oss.driver.api.core.type.codec.TypeCodec;
import com.datastax.oss.driver.api.core.type.reflect.GenericType;
import zipkin2.Endpoint;
import zipkin2.internal.Nullable;

import static zipkin2.storage.cassandra.CassandraUtil.inetAddressOrNull;

final class EndpointCodec extends MappingCodec<UdtValue, Endpoint> {

  EndpointCodec(TypeCodec<UdtValue> innerCodec) {
    super(innerCodec, GenericType.of(Endpoint.class));
  }

  @Override public UserDefinedType getCqlType() {
    return (UserDefinedType) super.getCqlType();
  }

  @Nullable @Override protected Endpoint innerToOuter(@Nullable UdtValue value) {
    if (value == null) return null;
    Endpoint.Builder builder =
      Endpoint.newBuilder().serviceName(value.getString("service")).port(value.getInt("port"));
    builder.parseIp(value.getInetAddress("ipv4"));
    builder.parseIp(value.getInetAddress("ipv6"));
    return builder.build();
  }

  @Nullable @Override protected UdtValue outerToInner(@Nullable Endpoint endpoint) {
    if (endpoint == null) return null;
    UdtValue result = getCqlType().newValue();
    result.setString("service", endpoint.serviceName());
    result.setInetAddress("ipv4", inetAddressOrNull(endpoint.ipv4(), endpoint.ipv4Bytes()));
    result.setInetAddress("ipv6", inetAddressOrNull(endpoint.ipv6(), endpoint.ipv6Bytes()));
    result.setInt("port", endpoint.portAsInt());
    return result;
  }
}
