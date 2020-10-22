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

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.PreparedStatement;
import com.datastax.oss.driver.api.core.cql.ResultSet;
import zipkin2.storage.cassandra.CassandraStorage.SessionFactory;

import static zipkin2.storage.cassandra.Schema.TABLE_SPAN;

final class LazySession {
  final SessionFactory sessionFactory;
  final CassandraStorage storage;
  volatile CqlSession session;
  volatile PreparedStatement healthCheck; // guarded by session
  volatile Schema.Metadata metadata; // guarded by session

  LazySession(SessionFactory sessionFactory, CassandraStorage storage) {
    this.sessionFactory = sessionFactory;
    this.storage = storage;
  }

  CqlSession get() {
    if (session == null) {
      synchronized (this) {
        if (session == null) {
          session = sessionFactory.create(storage);
          // cached here to warn only once when schema problems exist
          metadata = Schema.readMetadata(session, storage.keyspace);
          healthCheck = session.prepare("SELECT trace_id FROM " + TABLE_SPAN + " limit 1");
        }
      }
    }
    return session;
  }

  Schema.Metadata metadata() {
    get();
    return metadata;
  }

  ResultSet healthCheck() {
    get();
    return session.execute(healthCheck.bind());
  }

  void close() {
    CqlSession maybeSession = session;
    if (maybeSession != null) {
      session.close();
      session = null;
    }
  }
}
