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
import com.datastax.oss.driver.api.core.cql.Row;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionStage;
import zipkin2.Call;
import zipkin2.DependencyLink;
import zipkin2.internal.Dependencies;
import zipkin2.internal.DependencyLinker;
import zipkin2.storage.cassandra.internal.call.ResultSetFutureCall;

import static zipkin2.storage.cassandra.v1.Tables.DEPENDENCIES;

final class SelectDependencies extends ResultSetFutureCall<List<DependencyLink>> {
  static final class Factory {
    final CqlSession session;
    final PreparedStatement preparedStatement;

    Factory(CqlSession session) {
      this.session = session;
      this.preparedStatement = session.prepare("SELECT dependencies"
        + " FROM " + DEPENDENCIES
        + " WHERE day IN ?");
    }

    Call<List<DependencyLink>> create(long endTs, long lookback) {
      List<Instant> days = CassandraUtil.getDays(endTs, lookback);
      return new SelectDependencies(this, days);
    }
  }

  final Factory factory;
  final List<Instant> epochDays;

  SelectDependencies(Factory factory, List<Instant> epochDays) {
    this.factory = factory;
    this.epochDays = epochDays;
  }

  @Override protected CompletionStage<AsyncResultSet> newCompletionStage() {
    return factory.session.executeAsync(factory.preparedStatement.boundStatementBuilder()
      .setList(0, epochDays, Instant.class).build());
  }

  @Override public String toString() {
    return "SelectDependencies{days=" + epochDays + "}";
  }

  @Override public SelectDependencies clone() {
    return new SelectDependencies(factory, epochDays);
  }

  @Override public List<DependencyLink> map(AsyncResultSet rs) {
    List<DependencyLink> unmerged = new ArrayList<>();
    for (Row row : rs.currentPage()) {
      ByteBuffer encodedDayOfDependencies = row.getBytesUnsafe(0);
      unmerged.addAll(Dependencies.fromThrift(encodedDayOfDependencies).links());
    }
    return DependencyLinker.merge(unmerged);
  }
}
