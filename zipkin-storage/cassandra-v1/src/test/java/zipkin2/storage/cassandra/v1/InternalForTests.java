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

import com.datastax.oss.driver.api.core.CqlIdentifier;
import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.ProtocolVersion;
import com.datastax.oss.driver.api.core.context.DriverContext;
import com.datastax.oss.driver.api.core.cql.PreparedStatement;
import com.datastax.oss.driver.api.core.metadata.Metadata;
import com.datastax.oss.driver.api.core.metadata.schema.KeyspaceMetadata;
import com.datastax.oss.driver.api.core.metadata.schema.TableMetadata;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import zipkin2.DependencyLink;
import zipkin2.internal.Dependencies;

import static java.util.Collections.singletonMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static zipkin2.storage.cassandra.v1.Tables.DEPENDENCIES;
import static zipkin2.storage.cassandra.v1.Tables.REMOTE_SERVICE_NAMES;
import static zipkin2.storage.cassandra.v1.Tables.TRACES;

class InternalForTests {

  static CqlSession mockSession() {
    CqlSession session = mock(CqlSession.class);
    Metadata metadata = mock(Metadata.class);

    DriverContext context = mock(DriverContext.class);
    when(session.getContext()).thenReturn(context);
    when(context.getProtocolVersion()).thenReturn(ProtocolVersion.V4);

    KeyspaceMetadata keyspaceMetadata = mock(KeyspaceMetadata.class);
    when(session.getMetadata()).thenReturn(metadata);
    when(metadata.getKeyspace("zipkin")).thenReturn(Optional.of(keyspaceMetadata));
    when(session.getKeyspace()).thenReturn(Optional.of(CqlIdentifier.fromCql("zipkin")));

    TableMetadata tableMetadata = mock(TableMetadata.class);
    when(keyspaceMetadata.getTable(TRACES)).thenReturn(Optional.of(tableMetadata));

    Map<String, String> compaction =
      singletonMap("class", "org.apache.cassandra.db.compaction.DateTieredCompactionStrategy");
    when(tableMetadata.getOptions())
      .thenReturn(singletonMap(CqlIdentifier.fromCql("compaction"), compaction));

    when(keyspaceMetadata.getTable(REMOTE_SERVICE_NAMES))
      .thenReturn(Optional.of(mock(TableMetadata.class)));
    return session;
  }

  static void writeDependencyLinks(
    CassandraStorage storage, List<DependencyLink> links, long midnightUTC) {
    Dependencies deps = Dependencies.create(midnightUTC, midnightUTC /* ignored */, links);
    ByteBuffer thrift = deps.toThrift();

    PreparedStatement prepared =
      storage.session().prepare("INSERT INTO " + DEPENDENCIES + " (day,dependencies) VALUES (?,?)");

    storage.session().execute(prepared.bind(Instant.ofEpochMilli(midnightUTC), thrift));
  }
}
