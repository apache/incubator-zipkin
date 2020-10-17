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

import com.datastax.driver.core.PreparedStatement;
import com.datastax.driver.core.ResultSetFuture;
import com.datastax.driver.core.Session;
import com.datastax.driver.core.querybuilder.Insert;
import zipkin2.internal.DelayLimiter;
import zipkin2.storage.cassandra.internal.call.DeduplicatingInsert;

import static com.datastax.driver.core.querybuilder.QueryBuilder.bindMarker;
import static com.datastax.driver.core.querybuilder.QueryBuilder.insertInto;
import static com.datastax.driver.core.querybuilder.QueryBuilder.ttl;
import static zipkin2.storage.cassandra.v1.Tables.SERVICE_NAMES;

final class InsertServiceName extends DeduplicatingInsert<String> {
  static final class Factory extends DeduplicatingInsert.Factory<String> {
    final Session session;
    final PreparedStatement preparedStatement;

    Factory(CassandraStorage storage, int indexTtl) {
      super(storage.autocompleteTtl, storage.autocompleteCardinality);
      session = storage.session();
      Insert insertQuery = insertInto(SERVICE_NAMES)
        .value("service_name", bindMarker());
      if (indexTtl > 0) insertQuery.using(ttl(indexTtl));
      preparedStatement = session.prepare(insertQuery);
    }

    @Override protected InsertServiceName newCall(String input) {
      return new InsertServiceName(this, delayLimiter, input);
    }
  }

  final Factory factory;

  InsertServiceName(Factory factory, DelayLimiter<String> delayLimiter, String service_name) {
    super(delayLimiter, service_name);
    this.factory = factory;
  }

  @Override protected ResultSetFuture newFuture() {
    return factory.session.executeAsync(factory.preparedStatement.bind().setString(0, input));
  }

  @Override public String toString() {
    return "InsertServiceName(" + input + ")";
  }

  @Override public InsertServiceName clone() {
    return new InsertServiceName(factory, delayLimiter, input);
  }
}
