# storage-cassandra

This is a CQL-based Cassandra storage component, built upon the [Zipkin v1 thrift model](https://github.com/openzipkin/zipkin-api/tree/master/thrift).
This uses Cassandra 2.2+ features, but is tested against the latest patch of Cassandra 3.11.

`CassandraSpanStore.getDependencies()` returns pre-aggregated dependency links (ex via [zipkin-dependencies](https://github.com/openzipkin/zipkin-dependencies)).

The implementation uses the [Datastax Java Driver 4.x](https://github.com/datastax/java-driver).

`zipkin2.storage.cassandra.v1.CassandraStorage.Builder` includes defaults that will
operate against a local Cassandra installation.

## Logging
Since the underlying driver uses SLF4J, Zipkin's storage layer also uses
this (note SLF4J is supported out-of-the-box with no configuration in
zipkin-server).

Zipkin's storage layer logs to the category "zipkin2.storage.cassandra",
but you may wish to see the entire "zipkin2" when troubleshooting.

If you want to see requests and latency, set the logging category
"com.datastax.oss.driver.internal.core.tracker.RequestLogger" to DEBUG.
TRACE includes query values.

See [Request Logger](https://docs.datastax.com/en/developer/java-driver/4.9/manual/core/request_tracker/#request-logger) for more details.

## Testing
This module conditionally runs integration tests against a local Cassandra instance.

Tests are configured to automatically access Cassandra started with its defaults.
To ensure tests execute, download a Cassandra 2.2+ distribution, extract it, and run `bin/cassandra`.

If you run tests via Maven or otherwise when Cassandra is not running,
you'll notice tests are silently skipped.
```
Results :

Tests run: 62, Failures: 0, Errors: 0, Skipped: 48
```

This behaviour is intentional: We don't want to burden developers with
installing and running all storage options to test unrelated change.
That said, all integration tests run on pull request via Travis.

## Tuning
This component is tuned to help reduce the size of indexes needed to perform query operations. The most important aspects are described below. See [CassandraStorage](src/main/java/zipkin/storage/cassandra/CassandraStorage.java) for details.

### Autocomplete indexing
Redundant requests to store autocomplete values are ignored for an hour
to reduce load. This is implemented by
[DelayLimiter](../../zipkin/src/main/java/zipkin2/internal/DelayLimiter.java)

### Trace indexing
Indexing of traces are optimized by default. This reduces writes to Cassandra at the cost of memory
needed to cache state. This cache is tunable based on your typical span count.

Core annotations (ex "sr"), are not written to `annotations_index`, as they aren't intended for use
in user queries. Also, tag values longer than 256 characters are not indexed. These optimizations
significantly limit writes per trace.

### Over-fetching on Trace indexes
User-supplied query limits are over-fetched according to a configured index fetch multiplier in
attempts to mitigate redundant data returned from index queries.

## Cassandra 2.1
While not supported, here are some notes if you are running the original
schema on Cassandra 2.1.

### GET /api/v2/traces without the serviceName query will fail
The zipkin-ui always specifies a service name, but the http api permits
leaving it out. This won't work in Cassandra 2.1

### Disable `CASSANDRA_ENSURE_SCHEMA`
Upgrades assume features that are present in Cassandra 2.2+. Do not have
zipkin upgrade your schema if you are running Cassandra 2.1.

If using zipkin-server, set `CASSANDRA_ENSURE_SCHEMA=false`.

Manually running below will add default ttls to the original schema.
```
ALTER TABLE zipkin.traces WITH default_time_to_live = 604800;
ALTER TABLE zipkin.service_span_name_index WITH default_time_to_live = 259200;
ALTER TABLE zipkin.service_name_index WITH default_time_to_live = 259200;
ALTER TABLE zipkin.span_names WITH default_time_to_live = 259200;
ALTER TABLE zipkin.annotations_index WITH default_time_to_live = 259200;
ALTER TABLE zipkin.dependencies WITH default_time_to_live = 259200;
ALTER TABLE zipkin.service_names WITH default_time_to_live = 259200;
```
