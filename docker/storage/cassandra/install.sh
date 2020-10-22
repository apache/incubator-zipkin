#!/bin/sh
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

# install script used only in building the docker image, but not at runtime.
# This uses relative path so that you can change the home dir without editing this file.
# This also trims dependencies to only those used at runtime.
set -eux

echo "*** Installing Cassandra and dependencies"
# BusyBux built-in tar doesn't support --strip=1
apk add --update --no-cache python2 tar
APACHE_MIRROR=$(wget -qO- https://www.apache.org/dyn/closer.cgi\?as_json\=1 | sed -n '/preferred/s/.*"\(.*\)"/\1/gp')
wget -qO- $APACHE_MIRROR/cassandra/$CASSANDRA_VERSION/apache-cassandra-$CASSANDRA_VERSION-bin.tar.gz | tar xz \
  --strip=1

# Merge in our custom configuration
sed -i '/enable_user_defined_functions: false/cenable_user_defined_functions: true' conf/cassandra.yaml

# Default conf for Cassandra 3.x does not work on modern JVMs due to many deprecated flags
sed -i '/-XX:ThreadPriorityPolicy=42/c\#-XX:ThreadPriorityPolicy=42' conf/jvm.options
sed -i '/-XX:+UseParNewGC/c\#-XX:+UseParNewGC' conf/jvm.options
sed -i '/-XX:+UseConcMarkSweepGC/c\#-XX:+UseConcMarkSweepGC' conf/jvm.options
sed -i '/-XX:+PrintGCDateStamps/c\#-XX:+PrintGCDateStamps' conf/jvm.options
sed -i '/-XX:+PrintHeapAtGC/c\#-XX:+PrintHeapAtGC' conf/jvm.options
sed -i '/-XX:+PrintTenuringDistribution/c\#-XX:+PrintTenuringDistribution' conf/jvm.options
sed -i '/-XX:+PrintGCApplicationStoppedTime/c\#-XX:+PrintGCApplicationStoppedTime' conf/jvm.options
sed -i '/-XX:+PrintPromotionFailure/c\#-XX:+PrintPromotionFailure' conf/jvm.options
sed -i '/-XX:+UseGCLogFileRotation/c\#-XX:+UseGCLogFileRotation' conf/jvm.options
sed -i '/-XX:NumberOfGCLogFiles=10/c\#-XX:NumberOfGCLogFiles=10' conf/jvm.options
sed -i '/-XX:GCLogFileSize=10M/c\#-XX:GCLogFileSize=10M' conf/jvm.options

# TODO: Add native snappy lib. Native loader stacktraces in the cassandra log as a results, which is distracting.

# Remove bash as Cassandra scripts we use don't have it, and it isn't required
sed -i 's~#!/bin/bash~#!/bin/sh~g' bin/*sh

echo "*** Starting Cassandra"
bin/cassandra -R

# Excessively long timeout to avoid having to create an ENV variable, decide its name, etc.
timeout=180
echo "Will wait up to ${timeout} seconds for Cassandra to come up before installing Schema"
while [ "$timeout" -gt 0 ] && ! bin/cqlsh -e 'SHOW VERSION' localhost > /dev/null 2>&1; do
    sleep 1
    timeout=$(($timeout - 1))
done

echo "*** Importing Scheme"
cat zipkin-schemas/cassandra-schema.cql | bin/cqlsh --debug localhost
cat zipkin-schemas/zipkin2-schema.cql | bin/cqlsh --debug localhost
cat zipkin-schemas/zipkin2-schema-indexes.cql | bin/cqlsh --debug localhost

echo "*** Adding custom UDFs to zipkin2 keyspace"
bin/cqlsh -e "CREATE FUNCTION zipkin2.plus (x bigint, y bigint) RETURNS NULL ON NULL INPUT RETURNS bigint LANGUAGE java AS 'return x+y;';"
bin/cqlsh -e "CREATE FUNCTION zipkin2.minus (x bigint, y bigint) RETURNS NULL ON NULL INPUT RETURNS bigint LANGUAGE java AS 'return x-y;';"
bin/cqlsh -e "CREATE FUNCTION zipkin2.toTimestamp (x bigint) RETURNS NULL ON NULL INPUT RETURNS timestamp LANGUAGE java AS 'return new java.util.Date(x/1000);';"
bin/cqlsh -e "CREATE FUNCTION zipkin2.value (x map<text,text>, y text) RETURNS NULL ON NULL INPUT RETURNS text LANGUAGE java AS 'return x.get(y);';"

echo "*** Stopping Cassandra"
pkill -f java

# Take a backup so that we can safely mount an empty volume over the data directory and maintain the schema
cp -R data/ data-backup/

echo "*** Cleaning Up"
rm -rf javadoc/ pylib/ tools/ lib/*.zip zipkin-schemas/

echo "*** Image build complete"
