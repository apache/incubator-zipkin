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
package zipkin2.storage.cassandra.internal.call;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.AsyncResultSet;
import com.datastax.oss.driver.api.core.cql.PreparedStatement;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import zipkin2.Call;

public final class InsertEntry extends DeduplicatingInsert<Map.Entry<String, String>> {
  public static final class Factory extends DeduplicatingInsert.Factory<Map.Entry<String, String>> {
    final CqlSession session;
    final PreparedStatement preparedStatement;

    public Factory(String statement, CqlSession session, long ttl, int cardinality) {
      this(statement, session, ttl, cardinality, 0);
    }

    /** Cassandra v1 has deprecated support for indexTtl. */
    public Factory(String statement, CqlSession session, long ttl, int cardinality, int indexTtl) {
      super(ttl, cardinality);
      this.session = session;
      this.preparedStatement =
        session.prepare(indexTtl > 0 ? statement + " USING TTL " + indexTtl : statement);
    }

    @Override protected Call<Void> newCall(Map.Entry<String, String> input) {
      return new InsertEntry(this, input);
    }
  }

  final Factory factory;

  InsertEntry(Factory factory, Map.Entry<String, String> input) {
    super(factory.delayLimiter, input);
    this.factory = factory;
  }

  @Override protected CompletionStage<AsyncResultSet> newCompletionStage() {
    return factory.session.executeAsync(factory.preparedStatement.boundStatementBuilder()
      .setString(0, input.getKey())
      .setString(1, input.getValue()).build());
  }

  @Override public String toString() {
    return factory.preparedStatement.getQuery()
      .replace("(?,?)", "(" + input.getKey() + "," + input.getValue() + ")");
  }

  @Override public Call<Void> clone() {
    return new InsertEntry(factory, input);
  }
}
