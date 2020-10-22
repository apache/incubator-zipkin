/*
 * Copyright 2015-2020 The OpenZipkin Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package zipkin2.server.internal.cassandra3;

import java.io.Serializable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import zipkin2.storage.cassandra.CassandraStorage;

@ConfigurationProperties("zipkin.storage.cassandra3")
class ZipkinCassandra3StorageProperties implements Serializable { // for Spark jobs
  private static final long serialVersionUID = 0L;

  private String keyspace = "zipkin3";
  private String contactPoints = "localhost";
  private String localDc = "datacenter1";
  private int maxConnections = 8;
  private boolean ensureSchema = true;
  private boolean useSsl = false;
  private String username;
  private String password;
  /** See {@link CassandraStorage.Builder#indexFetchMultiplier(int)} */
  private int indexFetchMultiplier = 3;

  public String getKeyspace() {
    return keyspace;
  }

  public void setKeyspace(String keyspace) {
    this.keyspace = keyspace;
  }

  public String getContactPoints() {
    return contactPoints;
  }

  public void setContactPoints(String contactPoints) {
    this.contactPoints = contactPoints;
  }

  public String getLocalDc() {
    return localDc;
  }

  public void setLocalDc(String localDc) {
    this.localDc = "".equals(localDc) ? null : localDc;
  }

  public int getMaxConnections() {
    return maxConnections;
  }

  public void setMaxConnections(int maxConnections) {
    this.maxConnections = maxConnections;
  }

  public boolean isEnsureSchema() {
    return ensureSchema;
  }

  public void setEnsureSchema(boolean ensureSchema) {
    this.ensureSchema = ensureSchema;
  }

  public boolean isUseSsl() {
    return useSsl;
  }

  public void setUseSsl(boolean useSsl) {
    this.useSsl = useSsl;
  }

  public String getUsername() {
    return username;
  }

  public void setUsername(String username) {
    this.username = "".equals(username) ? null : username;
  }

  public String getPassword() {
    return password;
  }

  public void setPassword(String password) {
    this.password = "".equals(password) ? null : password;
  }

  public int getIndexFetchMultiplier() {
    return indexFetchMultiplier;
  }

  public void setIndexFetchMultiplier(int indexFetchMultiplier) {
    this.indexFetchMultiplier = indexFetchMultiplier;
  }

  public CassandraStorage.Builder toBuilder() {
    return CassandraStorage.newBuilder()
      .keyspace(keyspace)
      .contactPoints(contactPoints)
      .localDc(localDc)
      .maxConnections(maxConnections)
      .ensureSchema(ensureSchema)
      .useSsl(useSsl)
      .username(username)
      .password(password)
      .indexFetchMultiplier(indexFetchMultiplier);
  }
}
