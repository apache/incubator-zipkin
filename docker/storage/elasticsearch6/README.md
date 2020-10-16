## zipkin-elasticsearch6 Docker image

The `zipkin-elasticsearch6` testing image runs Elasticsearch 6.x for [Elasticsearch storage](../../../zipkin-storage/elasticsearch)
integration.

To build `openzipkin/zipkin-elasticsearch6:test`, from the top level of the repository, run:
```bash
$ docker/build_image zipkin-elasticsearch6
```

You can use the env variable `JAVA_OPTS` to change settings such as heap size for Elasticsearch.

#### Host setup
Elasticsearch is [strict](https://github.com/docker-library/docs/tree/master/elasticsearch#host-setup)
about virtual memory. You will need to adjust accordingly (especially if you notice Elasticsearch crash!)

```bash
# If docker is running on your host machine, adjust the kernel setting directly
$ sudo sysctl -w vm.max_map_count=262144

# If using docker-machine/Docker Toolbox/Boot2Docker, remotely adjust the same
$ docker-machine ssh default "sudo sysctl -w vm.max_map_count=262144"
```
