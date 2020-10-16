#!/bin/sh
#
# Copyright 2016-2020 The OpenZipkin Authors
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

set -eux

# This script decides based on $RELEASE_VERSION whether to build or download the binaries we need.
if [ "$RELEASE_VERSION" = "master" ]
then
  echo "*** Building from source..."
  # Use the same command as we suggest in zipkin-server/README.md
  #  * Uses mvn not ./mvnw to reduce layer size: we control the Maven version in Docker
  (cd /code; mvn -T1C -q --batch-mode -DskipTests -Dlicense.skip=true --also-make -pl zipkin-server package)
  cp /code/zipkin-server/target/zipkin-server-*-exec.jar zipkin-exec.jar
  cp /code/zipkin-server/target/zipkin-server-*-slim.jar zipkin-slim.jar
else
  echo "*** Downloading from Maven...."
  for classifier in exec slim; do
    # This prefers Maven central, but uses our release repository if it isn't yet synced.
    mvn --batch-mode org.apache.maven.plugins:maven-dependency-plugin:get \
        -DremoteRepositories=bintray::::https://dl.bintray.com/openzipkin/maven -Dtransitive=false \
        -Dartifact=io.zipkin:zipkin-server:${RELEASE_VERSION}:jar:${classifier}
    # Move, don't copy, large archives to prevent zipkin-builder image cache bloat
    find ~/.m2/repository -name zipkin-server-${RELEASE_VERSION}-${classifier}.jar -exec mv {} zipkin-${classifier}.jar \;
  done
fi

# sanity check!
test -f zipkin-exec.jar
test -f zipkin-slim.jar

mkdir zipkin zipkin-slim zipkin-lens
(mv zipkin-exec.jar zipkin/ && cd zipkin && jar xf *.jar && rm *.jar)
(mv zipkin-slim.jar zipkin-slim/ && cd zipkin-slim && jar xf *.jar && rm *.jar)
(jar xf zipkin/BOOT-INF/lib/zipkin-lens-*.jar && rm -rf zipkin-lens/META-INF)
