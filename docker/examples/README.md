# Zipkin Docker Examples

This project is configured to run docker containers using
[docker-compose](https://docs.docker.com/compose/). Note that the default
configuration requires docker-compose 1.6.0+ and docker-engine 1.10.0+.

To start the default docker-compose configuration, run:

```bash
$ docker-compose up
```

View the web UI at $(docker ip):9411. Traces are stored in memory.

To see specific traces in the UI, select "zipkin-server" in the dropdown and
then click the "Find Traces" button.

## Elasticsearch

You can store traces in [Elasticsearch](../storage/elasticsearch7/README.md) instead of memory,
using the `docker-compose-elasticsearch.yml` file. This configuration starts `zipkin`,
`zipkin-elasticsearch` and `zipkin-dependencies` (cron job) in their own containers.

To start the Elasticsearch-backed configuration, run:

```bash
$ docker-compose -f docker-compose-elasticsearch.yml up
```

## Cassandra

You can store traces in [Cassandra](../storage/cassandra/README.md) instead of memory, using the
`docker-compose-cassandra.yml` file. This configuration starts `zipkin`, `zipkin-cassandra` and
`zipkin-dependencies` (cron job) in their own containers.

To start the Cassandra-backed configuration, run:

```bash
$ docker-compose -f docker-compose-cassandra.yml up
```

## MySQL

You can store traces in [MySQL](../storage/mysql/README.md) instead of memory, using the
`docker-compose-cassandra.yml` file. This configuration starts `zipkin`, `zipkin-mysql` and
`zipkin-dependencies` (cron job) in their own containers.

To start the MySQL-backed configuration, run:

```bash
$ docker-compose -f docker-compose-mysql.yml up
```

## Kafka

The docker-compose configuration can be extended to host a test [Kafka broker](../collector/kafka/README.md)
and activate the [Kafka collector](../../zipkin-collector/kafka) using the `docker-compose-kafka.yml`
file. That file employs [docker-compose overrides](https://docs.docker.com/compose/extends/#multiple-compose-files)
to add a Kafka+ZooKeeper container and relevant settings.

To add Kafka configuration, run:
```bash
$ docker-compose -f docker-compose.yml -f docker-compose-kafka.yml up
```

Then configure the [Kafka sender](https://github.com/openzipkin/zipkin-reporter-java/blob/master/kafka11/src/main/java/zipkin2/reporter/kafka11/KafkaSender.java) using a `bootstrapServers` value of `host.docker.internal:9092` if your application is inside the same docker network or `localhost:19092` if not, but running on the same host.

In other words, if you are running a sample application on your laptop, you would use `localhost:19092` bootstrap server to send spans to the Kafka broker running in Docker.

### Docker machine and Kafka

If you are using Docker machine, adjust `KAFKA_ADVERTISED_HOST_NAME` in `docker-compose-kafka.yml`
and the `bootstrapServers` configuration of the kafka sender to match your Docker host IP (ex. 192.168.99.100:19092).

# Example

The docker-compose configuration can be extended to host an [example application](https://github.com/openzipkin/brave-example)
using the `docker-compose-example.yml` file. That file employs [docker-compose overrides](https://docs.docker.com/compose/extends/#multiple-compose-files)
to add a "frontend" and "backend" service.

To add the example configuration, run:
```bash
$ docker-compose -f docker-compose.yml -f docker-compose-example.yml up
```

Once the services start, open http://localhost:8081/
* This calls the backend (http://127.0.0.1:9000/api) and shows its result: a formatted date.

Afterwards, you can view traces that went through the backend via http://127.0.0.1:9411/zipkin?serviceName=backend

## UI

The docker-compose configuration can be extended to host the [UI](../lens/README.md) on port 80
using the `docker-compose-ui.yml` file. That file employs
[docker-compose overrides](https://docs.docker.com/compose/extends/#multiple-compose-files)
to add an NGINX container and relevant settings.

To start the NGINX configuration, run:

```bash
$ docker-compose -f docker-compose.yml -f docker-compose-ui.yml up
```

This container doubles as a skeleton for creating proxy configuration around
Zipkin like authentication, dealing with CORS with zipkin-js apps, or
terminating SSL.


If you want to run the zipkin-ui standalone against a remote zipkin server, you
need to set `ZIPKIN_BASE_URL` accordingly:

```bash
$ docker run -d -p 80:80 \
  -e ZIPKIN_BASE_URL=http://myfavoritezipkin:9411 \
  openzipkin/zipkin-ui
```

## Prometheus

Zipkin comes with a built-in Prometheus metric exporter. The
`docker-compose-prometheus.yml` file starts Prometheus configured to scrape
Zipkin, exposes it on port `9090`. You can open `$DOCKER_HOST_IP:9090` and
start exploring metrics (available on the `/prometheus` endpoint of Zipkin).

`docker-compose-prometheus.yml` also starts a Grafana with authentication
disabled, exposing it on port 3000. On startup it's configured with the
Prometheus instance started by `docker-compose` as a data source, and imports
the dashboard published at https://grafana.com/dashboards/1598. This means that,
after running `docker-compose  ... -f docker-compose-prometheus.yml up`, you
can open `$DOCKER_IP:3000/dashboard/db/zipkin-prometheus` and play around with
the dashboard.
