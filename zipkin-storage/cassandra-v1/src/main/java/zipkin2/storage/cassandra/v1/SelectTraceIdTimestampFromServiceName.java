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
import com.datastax.oss.driver.api.core.cql.BoundStatementBuilder;

import static zipkin2.storage.cassandra.v1.IndexTraceId.BUCKETS;
import static zipkin2.storage.cassandra.v1.Tables.SERVICE_NAME_INDEX;

// select service_name,TOUNIXTIMESTAMP(ts),bigintAsBlob(trace_id) from service_name_index;
final class SelectTraceIdTimestampFromServiceName extends SelectTraceIdIndex.Factory<String> {
  SelectTraceIdTimestampFromServiceName(CqlSession session) {
    super(session, SERVICE_NAME_INDEX, "service_name", 2);
  }

  @Override String selectStatement(String table, String partitionKeyColumn) {
    return super.selectStatement(table, partitionKeyColumn)
      + " AND bucket IN ?";
  }

  @Override void bindPartitionKey(BoundStatementBuilder bound, String serviceName) {
    bound
      .setString(0, serviceName)
      .setList(1, BUCKETS, Integer.class);
  }
}
