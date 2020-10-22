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
import com.datastax.oss.driver.api.core.metadata.schema.KeyspaceMetadata;
import com.datastax.oss.driver.api.core.metadata.schema.RelationMetadata;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zipkin2.internal.Nullable;

import static zipkin2.storage.cassandra.internal.KeyspaceMetadataUtil.getDefaultTtl;
import static zipkin2.storage.cassandra.internal.Resources.resourceToString;
import static zipkin2.storage.cassandra.v1.Tables.AUTOCOMPLETE_TAGS;
import static zipkin2.storage.cassandra.v1.Tables.REMOTE_SERVICE_NAMES;
import static zipkin2.storage.cassandra.v1.Tables.TRACES;

final class Schema {
  static final Logger LOG = LoggerFactory.getLogger(Schema.class);

  static final String SCHEMA = "/cassandra-schema.cql";
  static final String UPGRADE_1 = "/cassandra-schema-upgrade-1.cql";
  static final String UPGRADE_2 = "/cassandra-schema-upgrade-2.cql";
  static final String UPGRADE_3 = "/cassandra-schema-upgrade-3.cql";

  static Metadata readMetadata(CqlSession session, String keyspace) {
    KeyspaceMetadata keyspaceMetadata = ensureKeyspaceMetadata(session, keyspace);

    Map<String, String> replication = keyspaceMetadata.getReplication();
    if ("SimpleStrategy".equals(replication.get("class"))) {
      if ("1".equals(replication.get("replication_factor"))) {
        LOG.warn("running with RF=1, this is not suitable for production. Optimal is 3+");
      }
    }

    String compactionClass = compactionClassForTable(keyspaceMetadata, "traces");

    boolean hasDefaultTtl = hasUpgrade1_defaultTtl(keyspaceMetadata);
    if (!hasDefaultTtl) {
      LOG.warn(
        "schema lacks default ttls: apply {}, or set CassandraStorage.ensureSchema=true",
        UPGRADE_1);
    }

    boolean hasAutocompleteTags = hasUpgrade2_autocompleteTags(keyspaceMetadata);
    if (!hasAutocompleteTags) {
      LOG.warn(
        "schema lacks autocomplete indexing: apply {}, or set CassandraStorage.ensureSchema=true",
        UPGRADE_2);
    }

    boolean hasRemoteService = hasUpgrade3_remoteService(keyspaceMetadata);
    if (!hasRemoteService) {
      LOG.warn(
        "schema lacks remote service indexing: apply {}, or set CassandraStorage.ensureSchema=true",
        UPGRADE_3);
    }

    ProtocolVersion protocolVersion = session.getContext().getProtocolVersion();

    return new Metadata(protocolVersion, compactionClass, hasDefaultTtl, hasAutocompleteTags,
      hasRemoteService);
  }

  @Nullable static KeyspaceMetadata getKeyspaceMetadata(CqlSession session, String keyspace) {
    com.datastax.oss.driver.api.core.metadata.Metadata metadata = session.getMetadata();
    return metadata.getKeyspace(keyspace).orElse(null);
  }

  static final class Metadata {
    final ProtocolVersion protocolVersion;
    final String compactionClass;
    final boolean hasDefaultTtl, hasAutocompleteTags, hasRemoteService;

    Metadata(ProtocolVersion protocolVersion, String compactionClass, boolean hasDefaultTtl,
      boolean hasAutocompleteTags, boolean hasRemoteService) {
      this.protocolVersion = protocolVersion;
      this.compactionClass = compactionClass;
      this.hasDefaultTtl = hasDefaultTtl;
      this.hasAutocompleteTags = hasAutocompleteTags;
      this.hasRemoteService = hasRemoteService;
    }
  }

  static KeyspaceMetadata ensureKeyspaceMetadata(CqlSession session, String keyspace) {
    KeyspaceMetadata keyspaceMetadata = getKeyspaceMetadata(session, keyspace);
    if (keyspaceMetadata == null) {
      throw new IllegalStateException(
        String.format(
          "Cannot read keyspace metadata for keyspace: %s and cluster: %s",
          keyspace, session.getMetadata().getClusterName()));
    }
    return keyspaceMetadata;
  }

  static void ensureExists(String keyspace, CqlSession session) {
    KeyspaceMetadata keyspaceMetadata = getKeyspaceMetadata(session, keyspace);
    if (keyspaceMetadata == null || !keyspaceMetadata.getTable("traces").isPresent()) {
      LOG.info("Installing schema {}", SCHEMA);
      applyCqlFile(keyspace, session, SCHEMA);
      // refresh metadata since we've installed the schema
      keyspaceMetadata = session.getMetadata().getKeyspace(keyspace).get();
    }
    if (!hasUpgrade1_defaultTtl(keyspaceMetadata)) {
      LOG.info("Upgrading schema {}", UPGRADE_1);
      applyCqlFile(keyspace, session, UPGRADE_1);
    }
    if (!hasUpgrade2_autocompleteTags(keyspaceMetadata)) {
      LOG.info("Upgrading schema {}", UPGRADE_2);
      applyCqlFile(keyspace, session, UPGRADE_2);
    }
    if (!hasUpgrade3_remoteService(keyspaceMetadata)) {
      LOG.info("Upgrading schema {}", UPGRADE_3);
      applyCqlFile(keyspace, session, UPGRADE_3);
    }
  }

  static boolean hasUpgrade1_defaultTtl(KeyspaceMetadata keyspaceMetadata) {
    // TODO: we need some approach to forward-check compatibility as well.
    //  backward: this code knows the current schema is too old.
    //  forward:  this code knows the current schema is too new.
    return getDefaultTtl(keyspaceMetadata, TRACES) > 0;
  }

  static boolean hasUpgrade2_autocompleteTags(KeyspaceMetadata keyspaceMetadata) {
    return keyspaceMetadata.getTable(AUTOCOMPLETE_TAGS).isPresent();
  }

  static boolean hasUpgrade3_remoteService(KeyspaceMetadata keyspaceMetadata) {
    return keyspaceMetadata.getTable(REMOTE_SERVICE_NAMES).isPresent();
  }

  static void applyCqlFile(String keyspace, CqlSession session, String resource) {
    for (String cmd : resourceToString(resource).split(";", 100)) {
      cmd = cmd.trim().replace(" zipkin", " " + keyspace);
      if (!cmd.isEmpty()) session.execute(cmd);
    }
  }

  // https://groups.google.com/a/lists.datastax.com/g/java-driver-user/c/N7Q0QzJ1zuc/m/xtBYgwq9BQAJ
  static String compactionClassForTable(KeyspaceMetadata keyspaceMetadata, String table) {
    return keyspaceMetadata.getTable(table)
      .map(RelationMetadata::getOptions)
      .map(options -> options.get(CqlIdentifier.fromCql("compaction")))
      .map(compaction -> (String) ((Map<?, ?>) compaction).get("class"))
      .orElseThrow(() -> new RuntimeException(String.format(
        "Cannot read compaction class for keyspace: %s table: %s",
        keyspaceMetadata.getName(), table)));
  }

  Schema() {
  }
}
