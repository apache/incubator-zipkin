# zipkin-docker rationale

## Why do we add HEALTHCHECK?
While most of our images are not production, we still add HEALTHCHECK for a better ad-hoc
and automation experience. Many of our setups will not operate without a service
dependency: having HEALTHCHECK present makes triage and scripting a bit easier.

HEALTHCHECK on our test image serves primarily three purposes:
 * ad-hoc or scripted status of health using docker ps instead of knowing Kafka commands
 * to allow manual usage of the docker-compose v2 service_healthy condition
 * support Docker Hub automated test service

Ex. The following command can be used ad-hoc or in scripts without the user knowing Kafka:
```bash
$ docker inspect --format='{{json .State.Health.Status}}' kafka-zookeeper
"unhealthy"
```

### Why do we change default timeouts?
We changed timeouts in order to mark success faster.

By default, the Docker health check runs after 30s, and if a failure occurs,
it waits 30s to try again. This implies a minimum of 30s before the server is
marked healthy.

https://docs.docker.com/engine/reference/builder/#healthcheck

We expect the server startup to take less than 10 seconds, even in a fresh
start. Some health checks will trigger a slow "first request" due to schema
setup (ex this is the case in Elasticsearch and Cassandra). However, we don't
want to force an initial delay of 30s as defaults would.

Instead, we lower the interval and timeout from 30s to 5s. If a server starts
in 7s and takes another 2s to install schema, it can still pass in 10s vs 30s.

We retain the 30s even if it would be an excessively long startup. This is to
accommodate test containers, which can boot slower than production sites, and
any knock-on effects of that, like slow dependent storage containers which are
simultaneously bootstrapping.

### Why default timeout to 5s for Kafka HEALTHCHECK?

Docker `HEALTHCHECK` marks a container unhealthy on the first failure that occurs past its start
period. It is important to avoid false negatives that are host contention in nature, as they can
break orchestration such as docker-compose.

Commands like `nc` will almost never timeout launching due to contention, even if they might timeout
on network connections themselves for the same reason.

`kafka-topics.sh` uses Java and a relatively large classpath. It can be slow when many other
processes are starting at the same time. For example, when launching many containers in Docker
Compose, `kafka-topics.sh` timed out after 2s even though the prior run succeeded. This broke a condition
which broke the rest of the automation. A 5s timeout is excessive usually, but avoided this problem.
