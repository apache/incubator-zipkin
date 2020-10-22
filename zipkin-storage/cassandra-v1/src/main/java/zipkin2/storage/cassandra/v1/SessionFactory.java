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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zipkin2.storage.cassandra.internal.SessionBuilder;

/**
 * Creates a session and ensures schema if configured. Closes the cluster and session if any
 * exception occurred.
 */
public interface SessionFactory {

  CqlSession create(CassandraStorage storage);

  final class Default implements SessionFactory {
    static final Logger LOG = LoggerFactory.getLogger(Schema.class);

    /**
     * Creates a session and ensures schema if configured. Closes the cluster and session if any
     * exception occurred.
     */
    @Override public CqlSession create(CassandraStorage cassandra) {
      CqlSession session = null;
      try {
        session = buildSession(cassandra);

        String keyspace = cassandra.keyspace;
        if (cassandra.ensureSchema) {
          Schema.ensureExists(cassandra.keyspace, session);
        } else {
          LOG.debug("Skipping schema check on keyspace {} as ensureSchema was false", keyspace);
        }

        session.execute("USE " + keyspace);

        return session;
      } catch (RuntimeException e) { // don't leak on unexpected exception!
        if (session != null) session.close();
        throw e;
      }
    }

    static CqlSession buildSession(CassandraStorage cassandra) {
      return SessionBuilder.buildSession(
        cassandra.contactPoints,
        cassandra.localDc,
        cassandra.poolingOptions,
        cassandra.authProvider,
        cassandra.useSsl
      );
    }
  }
}
