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

ARG java_version=15.0.0-15.27.17

FROM openzipkin/java:${java_version} as built

WORKDIR /code

# Copy Maven source tree into the Docker context
COPY . .

# Use the same command as we suggest in zipkin-server/README.md to hydrate the Maven and NPM cache
#  * Uses mvn not ./mvnw to reduce layer size: we control the Maven version independently in Docker
RUN mvn -T1C -q --batch-mode -DskipTests -Dlicense.skip=true --also-make -pl zipkin-server package

# zipkin-builder is JDK + artifact caches because DockerHub doesn't support another way to update
# cache between builds.
FROM openzipkin/java:${java_version} as zipkin-builder

COPY --from=built /root/.m2 /root/.m2
COPY --from=built /root/.npm /root/.npm
