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

COPY . /code/

#####
# zipkin-builder - An image that caches build repositories (.m2, .npm) to speed up subsequent builds.
#####

FROM openzipkin/zipkin-builder as install

WORKDIR /install

# Conditions aren't supported in Dockerfile instructions, so we copy source even if it isn't used.
COPY --from=scratch /code /code

# This will either be "master" or a real version ex. "2.4.5"
ARG release_version
ENV RELEASE_VERSION=$release_version
COPY docker/bin/install.sh /tmp/
RUN /tmp/install.sh && rm /tmp/install.sh

# docker/hooks/post_push will republish what is otherwise built in docker/build/Dockerfile
# This is a copy/paste from builder/Dockerfile:
#
# zipkin-builder is JDK + artifact caches because DockerHub doesn't support another way to update
# cache between builds.
FROM openzipkin/java:${java_version} as zipkin-builder

COPY --from=install /root/.m2 /root/.m2
COPY --from=install /root/.npm /root/.npm

#####
# zipkin-ui - An image containing the Zipkin web frontend only, served by NGINX
#####

FROM nginx:1.18-alpine as zipkin-ui
LABEL MAINTAINER OpenZipkin "http://zipkin.io/"

ENV ZIPKIN_BASE_URL=http://zipkin:9411

# Add HEALTHCHECK and ENTRYPOINT scripts into the default search path
COPY --from=install /code/docker/lens/docker-bin/ /tmp/docker-bin/
RUN mv /tmp/docker-bin/* /usr/local/bin/ && rm -rf /tmp/docker-bin
# We use start period of 30s to avoid marking the container unhealthy on slow or contended CI hosts
HEALTHCHECK --interval=1s --start-period=30s --timeout=5s CMD ["docker-healthcheck"]
CMD ["start-nginx"]

# Add content and setup NGINX
COPY --from=install /install/zipkin-lens/ /var/www/html/zipkin/
COPY --from=install /code/docker/lens/nginx.conf /etc/nginx/conf.d/zipkin.conf.template
RUN mkdir -p /var/tmp/nginx && chown -R nginx:nginx /var/tmp/nginx

EXPOSE 80

# Almost everything is common between the slim and normal build
FROM openzipkin/java:${java_version}-jre as base-server

# All content including binaries and logs write under WORKDIR
ARG USER=zipkin
WORKDIR /${USER}

# Ensure the process doesn't run as root
RUN adduser -g '' -h ${PWD} -D ${USER}

# Add HEALTHCHECK and ENTRYPOINT scripts into the default search path
COPY --from=install /code/docker/bin/ /tmp/docker-bin/
RUN mv /tmp/docker-bin/docker-healthcheck /usr/local/bin/ && \
    mv /tmp/docker-bin/start-zipkin /usr/local/bin/ && \
    rm -rf /tmp/docker-bin
# We use start period of 30s to avoid marking the container unhealthy on slow or contended CI hosts.
#
# If in production, you have a 30s startup, please report to https://gitter.im/openzipkin/zipkin
# including the values of the /health and /info endpoints as this would be unexpected.
HEALTHCHECK --interval=5s --start-period=30s --timeout=5s CMD ["docker-healthcheck"]

ENTRYPOINT ["start-zipkin"]

# Switch to the runtime user
USER ${USER}

EXPOSE 9411

#####
# zipkin-slim - An image containing the slim distribution of Zipkin server.
#####
FROM base-server as zipkin-slim

COPY --from=install --chown=${USER} /install/zipkin-slim/ /zipkin/

#####
# zipkin-server - An image containing the full distribution of Zipkin server.
#####
FROM base-server as zipkin

# 3rd party modules like zipkin-aws will apply profile settings with this
ENV MODULE_OPTS=

COPY --from=install --chown=${USER} /install/zipkin/ /zipkin/

# Zipkin's full distribution includes Scribe support (albeit disabled)
EXPOSE 9410 9411
