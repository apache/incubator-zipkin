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

ARG alpine_version

# We copy files from the context into a scratch container first to avoid a problem where docker and
# docker-compose don't share layer hashes https://github.com/docker/compose/issues/883 normally.
# COPY --from= works around the issue.
FROM scratch as scratch

COPY zipkin-storage/mysql-v1/src/main/resources/mysql.sql /zipkin-schemas/
COPY docker/storage/mysql/install.sh /install/
COPY docker/storage/mysql/docker-bin/* /docker-bin/

FROM alpine:${alpine_version} as install

WORKDIR /install

COPY --from=scratch /zipkin-schemas/* ./zipkin-schemas/
COPY --from=scratch /install/install.sh /tmp/
RUN /tmp/install.sh && rm /tmp/install.sh

# Use a small base image at runtime
FROM alpine:${alpine_version} as zipkin-mysql

# Add the mysql binary and handle installation edge cases
RUN apk add --no-cache mysql && \
    # Prevent "Bind on unix socket: No such file or directory"
    mkdir -p /run/mysqld/ && chown -R mysql /run/mysqld/ && \
    # Enable networking
    echo skip-networking=0 >> /etc/my.cnf && \
    echo bind-address=0.0.0.0 >> /etc/my.cnf && \
    # Remove large binaries
    (cd /usr/bin; rm mysql_* aria_* mysqlbinlog myis* test-connect-t mysqlslap innochecksum resolve* my_print_defaults sst_dump)

# Add HEALTHCHECK and ENTRYPOINT scripts into the default search path
COPY --from=scratch /docker-bin/* /usr/local/bin/
# We use start period of 30s to avoid marking the container unhealthy on slow or contended CI hosts
HEALTHCHECK --interval=1s --start-period=30s --timeout=5s CMD ["docker-healthcheck"]
ENTRYPOINT ["start-mysql"]

# All content including binaries and logs write under WORKDIR
ARG USER=mysql
WORKDIR /${USER}

# mysql package adds the mysql user
USER ${USER}

# Copy binaries and config we installed earlier
COPY --from=install --chown=${USER} /install .

EXPOSE 3306
