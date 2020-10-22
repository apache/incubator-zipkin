# storage-cassandra

This is a CQL-based Cassandra storage component, built upon the [Zipkin v2 api and model](https://zipkin.io/zipkin-api/#/default/post_spans).
This uses Cassandra 3.11.3+ features, but is tested against the latest patch of Cassandra 3.11.

`CassandraSpanStore.getDependencies()` returns pre-aggregated dependency links (ex via [zipkin-dependencies](https://github.com/openzipkin/zipkin-dependencies)).

The implementation uses the [Datastax Java Driver 4.x](https://github.com/datastax/java-driver).

`zipkin2.storage.cassandra.CassandraStorage.Builder` includes defaults that will operate against a local Cassandra installation.

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

This starts a docker container or attempts to re-use an existing cassandra node running on localhost.

If you run tests via Maven or otherwise when Cassandra is not running,
you'll notice tests are silently skipped.
```
Results :

Tests run: 62, Failures: 0, Errors: 0, Skipped: 48
```

This behaviour is intentional: We don't want to burden developers with
installing and running all storage options to test unrelated change.
That said, all integration tests run on pull request via Travis.

### Running a single test

To run a single integration test, use the following syntax:

```bash
$ ./mvnw -Dit.test='ITCassandraStorage$ITSpanStore#getTraces_duration' -pl zipkin-storage/cassandra clean verify
```

## Strict trace ID
By default, trace identifiers are written at the length received to indexes and span tables. This
means if instrumentation downgraded a 128-bit trace ID to 64-bit, it will appear in a search as two
traces. This situation is possible when using unmaintained or out-of-date trace instrumentation.

By setting strict trace ID to false, indexes only consider the right-most 16 chars, allowing mixed
trace length lookup at a slight collision risk. Retrieval of the 32-character trace ID is retained
by concatenating two columns in the span table like so:

```
trace_id            text, // when strictTraceId=false, only contains right-most 16 chars
trace_id_high       text, // when strictTraceId=false, contains left-most 16 chars if present
```

It is important to only set strict trace ID false during a transition and revert once complete, as
data written during this period is less intuitive for those using CQL, and contains a small
collision risk.

## Tuning
This component is tuned to help reduce the size of indexes needed to
perform query operations. The most important aspects are described below.
See [CassandraStorage](src/main/java/zipkin2/storage/cassandra/CassandraStorage.java) for details.

### Autocomplete indexing
Redundant requests to store autocomplete values are ignored for an hour
to reduce load. This is implemented by
[DelayLimiter](../../zipkin/src/main/java/zipkin2/internal/DelayLimiter.java)

### Trace indexing
Indexing in CQL is simplified by SASI, for example, reducing the number
of tables from 7 down to 4 (from the original cassandra schema). SASI
also moves some write-amplification from CassandraSpanConsumer into C*.

CassandraSpanConsumer directly writes to the tables `span`,
`trace_by_service_remote_service` `trace_by_service_span` and
`span_by_service`. The latter service based indexes amplify writes by a
factor of the distinct service names (`Span.localServiceName`).

Other amplification happens internally to C*, visible in the increase
write latency (although write latency remains performant at single digit
milliseconds).

#### `span` indexing
When queries only include a time range, trace ids are returned from a `ts_uuid`
range. This means no indexes are used when `GET /api/v2/traces` includes no
parameters or only `endTs` or `lookback`.

Two secondary (SASI) indexes support `annotationQuery` with `serviceName`:
* `annotation_query` supports LIKE (substring match) in `░error░error=500░`
* `l_service` in used in conjunction with annotation_query searches.

Ex, `GET /api/v2/traces?serviceName=tweetiebird&annotationQuery=error` results
in a single trace ID query against the above two indexes.

Note: annotations with values longer than 256 characters are not written to the
`annotation_query` SASI, as they aren't intended for use in user queries.

#### `trace_by_service_X` indexing

`trace_by_service_X` rows are answers to a shard of trace query. A query
request is broken down into possibly multiple shards based on our index
implementation.

Ex. `GET /api/v2/traces?serviceName=tweetiebird%remoteService=s3`

Breaks down into two query shards (this example omits time range and limit)
* `(service=tweetiebird, span=)`
* `(service=tweetiebird, remote_service=s3)`

The results intersect prioritizing on timestamp to return the distinct
trace IDs needed for a follow-up fetch.

#### `trace_by_service_remote_service` indexing

For example, a span in trace ID 1 named "get" created by "tweetiebird",
accessing the remote service "s3" results in the following row:

* `service=service1, span=remote_service, ts=timestamp_millis, trace_id=1`

This index is only used when the `remoteServiceName` query is used. Ex.
1. `GET /api/v2/traces?serviceName=tweetiebird&remoteServiceName=s3`
1. `GET /api/v2/traces?serviceName=tweetiebird&maxDuration=199500&remoteServiceName=s3`

#### `trace_by_service_span` indexing

For example, a span in trace ID 1 named "get" created by "service1",
taking 20 milliseconds results in the following rows:

1. `service=service1, span=get, trace_id=1, ts=timestamp_millis, duration=200`
2. `service=service1, span=, trace_id=1, ts=timestamp_millis, duration=200`

Here are corresponding queries that relate to the above rows:
1. `GET /api/v2/traces?serviceName=service1&spanName=get`
1. `GET /api/v2/traces?serviceName=service1&spanName=get&minDuration=200000`
1. `GET /api/v2/traces?serviceName=service1&minDuration=200000`
1. `GET /api/v2/traces?spanName=get`
1. `GET /api/v2/traces?maxDuration=199500`

As you'll notice, the duration component is optional, and stored in
millisecond resolution as opposed to microsecond (which the query represents).
The final query shows that the input is rounded up to the nearest millisecond.

The reason we can query on `duration` is due to a SASI index. Eventhough the
search granularity is millisecond, original duration data remains microsecond
granularity. Meanwhile, write performance is dramatically better than writing
discrete values, due to fewer distinct writes.

#### Disabling indexing
Indexing is a good default, but some sites who don't use Zipkin UI's
"Find a Trace" screen may want to disable indexing. This means [indexing schema](src/main/resources/zipkin2-schema-indexes.cql)
won't be setup, nor written at runtime. This increases write throughput
and reduces size on disk by not amplifying writes with index data.

[Disabling search](../../README.md#disabling-search) disables indexing.

### Time-To_live
Time-To-Live is default now at the table level. It cannot be overridden in write requests.

There's a different default TTL for trace data and indexes, 7 days vs 3 days respectively. The impact is that you can
retrieve a trace by ID for up to 7 days, but you can only search the last 3 days of traces (ex by service name).

### Compaction
Time-series data is compacted using TimeWindowCompactionStrategy, a known improved over DateTieredCompactionStrategy. Data is
optimised for queries within a single day. The penalty of reading multiple days is small, a few disk seeks, compared to the
otherwise overhead of reading a significantly larger amount of data.

### Benchmarking
Benchmarking the new datamodel demonstrates a significant performance improvement on reads. How much of this translates to the
Zipkin UI is hard to tell due to the complexity of CassandraSpanConsumer and how searches are possible. Benchmarking stress
profiles are found in traces-stress.yaml and trace_by_service_span-stress.yaml and span_by_service-stress.yaml.
