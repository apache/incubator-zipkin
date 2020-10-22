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

import com.datastax.oss.driver.api.core.cql.BoundStatementBuilder;

import static zipkin2.storage.cassandra.v1.Tables.SERVICE_REMOTE_SERVICE_NAME_INDEX;

// QueryRequest.remoteServiceName
final class IndexTraceIdByRemoteServiceName extends IndexTraceId.Factory {
  IndexTraceIdByRemoteServiceName(CassandraStorage storage, int indexTtl) {
    super("INSERT INTO " + SERVICE_REMOTE_SERVICE_NAME_INDEX
        + " (ts, trace_id, service_remote_service_name) VALUES (?,?,?)",
      storage, indexTtl);
  }

  @Override void bindPartitionKey(BoundStatementBuilder bound, String service_remote_service_name) {
    bound.setString(2, service_remote_service_name);
  }
}
