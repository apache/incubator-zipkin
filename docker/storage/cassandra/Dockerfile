#
# Copyright 2015-2020 The OpenZipkin Authors
#
# Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
# in compliance with the License. You may obtain a copy of the License at
#
# http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software distributed under the License
# is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
# or implied. See the License for the specific language governing permissions and limitations under
# the License.
#

ARG java_version

# We copy files from the context into a scratch container first to avoid a problem where docker and
# docker-compose don't share layer hashes https://github.com/docker/compose/issues/883 normally.
# COPY --from= works around the issue.
FROM scratch as scratch

COPY zipkin-storage/cassandra/src/main/resources/*.cql /zipkin-schemas/
COPY zipkin-storage/cassandra-v1/src/main/resources/cassandra-schema.cql /zipkin-schemas/
COPY docker/storage/cassandra/install.sh /install/
COPY docker/storage/cassandra/docker-bin/* /docker-bin/

# Until Cassandra supports recent JDKs, we can't re-use our builder
FROM openzipkin/java:${java_version} as install

# Use latest stable release here.
ENV CASSANDRA_VERSION=3.11.8

WORKDIR /install

COPY --from=scratch /zipkin-schemas/* ./zipkin-schemas/
COPY --from=scratch /install/install.sh /tmp/
RUN /tmp/install.sh && rm /tmp/install.sh

FROM openzipkin/java:${java_version}-jre as zipkin-cassandra

# Add HEALTHCHECK and ENTRYPOINT scripts into the default search path
COPY --from=scratch /docker-bin/* /usr/local/bin/
# We use start period of 30s to avoid marking the container unhealthy on slow or contended CI hosts
HEALTHCHECK --interval=1s --start-period=30s --timeout=5s CMD ["docker-healthcheck"]
ENTRYPOINT ["start-cassandra"]

# All content including binaries and logs write under WORKDIR
ARG USER=cassandra
WORKDIR /${USER}

# Ensure the process doesn't run as root
RUN adduser -g '' -h ${PWD} -D ${USER}
USER ${USER}

# Copy binaries and config we installed earlier
COPY --from=install --chown=${USER} /install .

# Set variables Cassandra's start script wants
#
# Integration test currently use a single container for all suites, without cleaning up keyspaces
# per method. For this reason, we use a higher heap size than 256m, which would be otherwise fine.
ENV JVM_OPTS="-Xms512m -Xmx512m -XX:+ExitOnOutOfMemoryError -Djava.net.preferIPv4Stack=true"

EXPOSE 9042
