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
import com.datastax.oss.driver.api.core.auth.AuthProvider;
import com.datastax.oss.driver.api.core.config.DriverOption;
import com.datastax.oss.driver.internal.core.auth.ProgrammaticPlainTextAuthProvider;
import java.util.Map;
import java.util.Set;
import zipkin2.Call;
import zipkin2.CheckResult;
import zipkin2.internal.Nullable;
import zipkin2.storage.AutocompleteTags;
import zipkin2.storage.ServiceAndSpanNames;
import zipkin2.storage.SpanConsumer;
import zipkin2.storage.SpanStore;
import zipkin2.storage.StorageComponent;
import zipkin2.storage.Traces;
import zipkin2.storage.cassandra.internal.CassandraStorageBuilder;
import zipkin2.storage.cassandra.internal.call.ResultSetFutureCall;

/**
 * CQL3 implementation of zipkin storage.
 *
 * <p>Queries are logged to the category "com.datastax.oss.driver.api.core.cql.QueryLogger" when
 * debug or trace is enabled via SLF4J. Trace level includes bound values.
 *
 * <p>Schema is installed by default from "/zipkin2-schema.cql"
 *
 * <p>When {@link StorageComponent.Builder#strictTraceId(boolean)} is disabled, span and index data
 * are uniformly written with 64-bit trace ID length. When retrieving data, an extra "trace_id_high"
 * field clarifies if a 128-bit trace ID was sent.
 */
public final class CassandraStorage extends StorageComponent {
  // @FunctionalInterface, except safe for lower language levels
  public interface SessionFactory {
    SessionFactory DEFAULT = new DefaultSessionFactory();

    CqlSession create(CassandraStorage storage);
  }

  public static Builder newBuilder() {
    return new Builder();
  }

  public static final class Builder extends CassandraStorageBuilder<Builder> {
    SessionFactory sessionFactory = SessionFactory.DEFAULT;

    Builder() {
      super(Schema.DEFAULT_KEYSPACE);
    }

    /** Keyspace to store span and index data. Defaults to "zipkin2" */
    @Override public Builder keyspace(String keyspace) {
      return super.keyspace(keyspace);
    }

    /**
     * Ensures that schema exists, if enabled tries to execute:
     * <ol>
     *   <li>io.zipkin.zipkin2:zipkin-storage-cassandra/zipkin2-schema.cql</li>
     *   <li>io.zipkin.zipkin2:zipkin-storage-cassandra/zipkin2-indexes.cql</li>
     * </ol>
     * Defaults to true.
     */
    @Override public Builder ensureSchema(boolean ensureSchema) {
      return super.ensureSchema(ensureSchema);
    }

    /** Override to control how sessions are created. */
    public Builder sessionFactory(SessionFactory sessionFactory) {
      if (sessionFactory == null) throw new NullPointerException("sessionFactory == null");
      this.sessionFactory = sessionFactory;
      return this;
    }

    @Override public CassandraStorage build() {
      AuthProvider authProvider = null;
      if (username != null) {
        authProvider = new ProgrammaticPlainTextAuthProvider(username, password);
      }
      return new CassandraStorage(strictTraceId, searchEnabled, autocompleteKeys, autocompleteTtl,
        autocompleteCardinality, contactPoints, localDc, poolingOptions(), authProvider, useSsl,
        sessionFactory, keyspace, ensureSchema, maxTraceCols, indexFetchMultiplier);
    }
  }

  final boolean strictTraceId, searchEnabled;
  final Set<String> autocompleteKeys;
  final int autocompleteTtl, autocompleteCardinality;

  final String contactPoints, localDc;
  final Map<DriverOption, Integer> poolingOptions;
  @Nullable final AuthProvider authProvider;
  final boolean useSsl;
  final String keyspace;
  final boolean ensureSchema;

  final int maxTraceCols, indexFetchMultiplier;

  final LazySession session;

  CassandraStorage(boolean strictTraceId, boolean searchEnabled, Set<String> autocompleteKeys,
    int autocompleteTtl, int autocompleteCardinality, String contactPoints, String localDc,
    Map<DriverOption, Integer> poolingOptions, AuthProvider authProvider, boolean useSsl,
    SessionFactory sessionFactory, String keyspace, boolean ensureSchema, int maxTraceCols,
    int indexFetchMultiplier) {
    // Assign generic configuration for all storage components
    this.strictTraceId = strictTraceId;
    this.searchEnabled = searchEnabled;
    this.autocompleteKeys = autocompleteKeys;
    this.autocompleteTtl = autocompleteTtl;
    this.autocompleteCardinality = autocompleteCardinality;

    // Assign configuration used to create a session
    this.contactPoints = contactPoints;
    this.localDc = localDc;
    this.poolingOptions = poolingOptions;
    this.authProvider = authProvider;
    this.useSsl = useSsl;
    this.ensureSchema = ensureSchema;
    this.keyspace = keyspace;

    // Assign configuration used to control queries
    this.maxTraceCols = maxTraceCols;
    this.indexFetchMultiplier = indexFetchMultiplier;

    this.session = new LazySession(sessionFactory, this);
  }

  /** close is typically called from a different thread */
  volatile boolean closeCalled;

  volatile CassandraSpanConsumer spanConsumer;
  volatile CassandraSpanStore spanStore;
  volatile CassandraAutocompleteTags tagStore;

  /** Lazy initializes or returns the session in use by this storage component. */
  CqlSession session() {
    return session.get();
  }

  Schema.Metadata metadata() {
    return session.metadata();
  }

  /** {@inheritDoc} Memoized in order to avoid re-preparing statements */
  @Override public SpanStore spanStore() {
    if (spanStore == null) {
      synchronized (this) {
        if (spanStore == null) {
          spanStore = new CassandraSpanStore(this);
        }
      }
    }
    return spanStore;
  }

  @Override public Traces traces() {
    return (Traces) spanStore();
  }

  @Override public ServiceAndSpanNames serviceAndSpanNames() {
    return (ServiceAndSpanNames) spanStore();
  }

  @Override public AutocompleteTags autocompleteTags() {
    if (tagStore == null) {
      synchronized (this) {
        if (tagStore == null) {
          tagStore = new CassandraAutocompleteTags(this);
        }
      }
    }
    return tagStore;
  }

  // Memoized in order to avoid re-preparing statements
  @Override public SpanConsumer spanConsumer() {
    if (spanConsumer == null) {
      synchronized (this) {
        if (spanConsumer == null) {
          spanConsumer = new CassandraSpanConsumer(this);
        }
      }
    }
    return spanConsumer;
  }

  @Override public boolean isOverCapacity(Throwable e) {
    return ResultSetFutureCall.isOverCapacity(e);
  }

  @Override public final String toString() {
    return "CassandraStorage{contactPoints=" + contactPoints + ", keyspace=" + keyspace + "}";
  }

  @Override public CheckResult check() {
    if (closeCalled) throw new IllegalStateException("closed");
    try {
      session.healthCheck();
    } catch (Throwable e) {
      Call.propagateIfFatal(e);
      return CheckResult.failed(e);
    }
    return CheckResult.OK;
  }

  @Override public void close() {
    if (closeCalled) return;
    session.close();
    closeCalled = true;
  }
}
