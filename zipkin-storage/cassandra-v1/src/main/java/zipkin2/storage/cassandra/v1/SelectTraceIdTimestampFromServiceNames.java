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
import java.util.Collections;
import java.util.List;
import java.util.Set;
import zipkin2.Call;
import zipkin2.storage.cassandra.v1.SelectTraceIdIndex.Input;

import static zipkin2.storage.cassandra.v1.IndexTraceId.BUCKETS;
import static zipkin2.storage.cassandra.v1.Tables.SERVICE_NAME_INDEX;

/**
 * Just like {@link SelectTraceIdTimestampFromServiceName} except provides an IN server-side query.
 *
 * <p>Note: this is only supported in Cassandra 2.2+
 */
final class SelectTraceIdTimestampFromServiceNames
  extends SelectTraceIdIndex.Factory<List<String>> {

  SelectTraceIdTimestampFromServiceNames(CqlSession session) {
    super(session, SERVICE_NAME_INDEX, "service_name", 2);
  }

  @Override String selectStatement(String table, String partitionKeyColumn) {
    return "SELECT trace_id,ts"
      + " FROM " + table
      + " WHERE " + partitionKeyColumn + " IN ?"
      + " AND bucket IN ?";
  }

  @Override void bindPartitionKey(BoundStatementBuilder bound, List<String> serviceNames) {
    bound
      .setList(0, serviceNames, String.class)
      .setList(1, BUCKETS, Integer.class);
  }

  Call.FlatMapper<List<String>, Set<Pair>> newFlatMapper(long endTs, long lookback, int limit) {
    return new FlatMapServiceNamesToInput(endTs, lookback, limit);
  }

  class FlatMapServiceNamesToInput implements Call.FlatMapper<List<String>, Set<Pair>> {
    final Input<List<String>> input;

    FlatMapServiceNamesToInput(long endTs, long lookback, int limit) {
      this.input = Input.create(Collections.emptyList(), endTs, lookback, limit);
    }

    @Override public Call<Set<Pair>> map(List<String> serviceNames) {
      return newCall(input.withPartitionKey(serviceNames));
    }

    @Override public String toString() {
      return "FlatMapServiceNamesToInput{" +
        input.toString().replace("Input", "SelectTraceIdTimestampFromServiceNames") + "}";
    }
  }
}
