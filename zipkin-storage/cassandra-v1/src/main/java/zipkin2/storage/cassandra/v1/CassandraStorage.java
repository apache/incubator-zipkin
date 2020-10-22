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
import com.datastax.oss.driver.api.core.auth.AuthProvider;
import com.datastax.oss.driver.api.core.config.DriverOption;
import com.datastax.oss.driver.internal.core.auth.ProgrammaticPlainTextAuthProvider;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import zipkin2.Call;
import zipkin2.CheckResult;
import zipkin2.storage.AutocompleteTags;
import zipkin2.storage.ServiceAndSpanNames;
import zipkin2.storage.SpanConsumer;
import zipkin2.storage.SpanStore;
import zipkin2.storage.StorageComponent;
import zipkin2.storage.Traces;
import zipkin2.storage.cassandra.internal.CassandraStorageBuilder;
import zipkin2.storage.cassandra.internal.call.DeduplicatingInsert;
import zipkin2.storage.cassandra.internal.call.ResultSetFutureCall;
import zipkin2.storage.cassandra.v1.Schema.Metadata;

/**
 * CQL3 implementation of zipkin storage.
 *
 * <p>Queries are logged to the category "com.datastax.oss.driver.api.core.cql.QueryLogger" when
 * debug or trace is enabled via SLF4J. Trace level includes bound values.
 *
 * <p>Redundant requests to store service or span names are ignored for an hour to reduce load.
 * This feature is implemented by {@link DeduplicatingInsert}.
 *
 * <p>Schema is installed by default from "/cassandra-schema.cql"
 */
public class CassandraStorage extends StorageComponent { // not final for mocking

  public static Builder newBuilder() {
    return new Builder();
  }

  public static final class Builder extends CassandraStorageBuilder<Builder> {
    SessionFactory sessionFactory = new SessionFactory.Default();
    int indexCacheMax = 100_000;
    int indexCacheTtl = 60;
    int spanTtl = (int) TimeUnit.DAYS.toSeconds(7);
    int indexTtl = (int) TimeUnit.DAYS.toSeconds(3);

    Builder() {
      super("zipkin");
    }

    /** Keyspace to store span and index data. Defaults to "zipkin" */
    @Override public Builder keyspace(String keyspace) {
      return super.keyspace(keyspace);
    }

    /**
     * Ensures that schema exists, if enabled tries to execute:
     * <ol>
     *   <li>io.zipkin.zipkin2:zipkin-storage-cassandra-v1/cassandra-schema.cql</li>
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

    /**
     * Time-to-live in seconds for span data. Defaults to 604800 (7 days)
     *
     * @deprecated current schema uses default ttls. This parameter will be removed in Zipkin 2
     */
    @Deprecated public Builder spanTtl(int spanTtl) {
      this.spanTtl = spanTtl;
      return this;
    }

    /**
     * Time-to-live in seconds for index data. Defaults to 259200 (3 days)
     *
     * @deprecated current schema uses default ttls. This parameter will be removed in Zipkin 2
     */
    @Deprecated public Builder indexTtl(int indexTtl) {
      this.indexTtl = indexTtl;
      return this;
    }

    /**
     * Indicates the maximum trace index metadata entries to cache. Zero disables the feature.
     * Defaults to 100000.
     *
     * <p>This is used to obviate redundant inserts into {@link Tables#SERVICE_NAME_INDEX}, {@link
     * Tables#SERVICE_REMOTE_SERVICE_NAME_INDEX}, {@link Tables#SERVICE_SPAN_NAME_INDEX} and {@link
     * Tables#ANNOTATIONS_INDEX}.
     *
     * <p>Corresponds to the count of rows inserted into between {@link #indexCacheTtl} and now.
     * This is bounded so that collectors that get large trace volume don't run out of memory before
     * {@link #indexCacheTtl} passes.
     *
     * <p>Note: It is hard to estimate precisely how many is the right number, particularly as
     * annotations and tag values are included in partition keys (meaning each cache entry can vary
     * in size considerably). A good guess might be 5 x spans per indexCacheTtl, memory permitting.
     */
    public Builder indexCacheMax(int indexCacheMax) {
      this.indexCacheMax = indexCacheMax;
      return this;
    }

    /**
     * Indicates how long in seconds to cache trace index metadata. Defaults to 1 minute. This is
     * only read when {@link #indexCacheMax} is greater than zero.
     *
     * <p>You should pick a value that is longer than the gap between the root span's timestamp and
     * its latest descendant span's timestamp. More simply, if 95% of your trace durations are under
     * 1 minute, use 1 minute.
     */
    public Builder indexCacheTtl(int indexCacheTtl) {
      this.indexCacheTtl = indexCacheTtl;
      return this;
    }

    @Override public CassandraStorage build() {
      AuthProvider authProvider = null;
      if (username != null) {
        authProvider = new ProgrammaticPlainTextAuthProvider(username, password);
      }
      return new CassandraStorage(strictTraceId, searchEnabled, autocompleteKeys, autocompleteTtl,
        autocompleteCardinality, contactPoints, localDc, poolingOptions(), authProvider, useSsl,
        sessionFactory, keyspace, ensureSchema, maxTraceCols, indexFetchMultiplier,
        indexCacheMax, indexCacheTtl, spanTtl, indexTtl // << cassandra v1 only
      );
    }
  }

  final boolean strictTraceId, searchEnabled;
  final Set<String> autocompleteKeys;
  final int autocompleteTtl, autocompleteCardinality;

  final String contactPoints, localDc;
  final Map<DriverOption, Integer> poolingOptions;
  final AuthProvider authProvider;
  final boolean useSsl;
  final String keyspace;
  final boolean ensureSchema;

  final int maxTraceCols, indexFetchMultiplier;

  final int indexCacheMax, indexCacheTtl;

  final int spanTtl, indexTtl;

  final LazySession session;

  CassandraStorage(boolean strictTraceId, boolean searchEnabled, Set<String> autocompleteKeys,
    int autocompleteTtl, int autocompleteCardinality, String contactPoints, String localDc,
    Map<DriverOption, Integer> poolingOptions, AuthProvider authProvider, boolean useSsl,
    SessionFactory sessionFactory, String keyspace, boolean ensureSchema, int maxTraceCols,
    int indexFetchMultiplier, int indexCacheMax, int indexCacheTtl, int spanTtl, int indexTtl) {
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

    // Client-side indexes use these properties
    this.indexCacheMax = indexCacheMax;
    this.indexCacheTtl = indexCacheTtl;

    // Deprecated index TTL modifiers
    this.spanTtl = spanTtl;
    this.indexTtl = indexTtl;

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

  Metadata metadata() {
    return session.metadata();
  }

  /** {@inheritDoc} Memoized in order to avoid re-preparing statements */
  @Override public SpanStore spanStore() {
    if (spanStore == null) {
      synchronized (this) {
        if (spanStore == null) spanStore = new CassandraSpanStore(this);
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
