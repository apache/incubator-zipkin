## zipkin-cassandra Docker image

The `zipkin-cassandra` testing image runs Cassandra 3.11.x initialized with Zipkin's schema for
[Cassandra storage](../../../zipkin-storage/cassandra) integration.

To build `openzipkin/zipkin-cassandra:test`, from the top level of the repository, run:
```bash
$ docker/build_image zipkin-cassandra
```
