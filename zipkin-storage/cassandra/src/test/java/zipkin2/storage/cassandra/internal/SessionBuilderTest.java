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
package zipkin2.storage.cassandra.internal;

import java.net.InetSocketAddress;
import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static zipkin2.storage.cassandra.internal.SessionBuilder.parseContactPoints;

public class SessionBuilderTest {
  @Test public void contactPoints_defaultsToLocalhost() {
    assertThat(parseContactPoints("localhost"))
      .containsExactly(new InetSocketAddress("127.0.0.1", 9042));
  }

  @Test public void contactPoints_defaultsToPort9042() {
    assertThat(parseContactPoints("1.1.1.1"))
      .containsExactly(new InetSocketAddress("1.1.1.1", 9042));
  }

  @Test public void contactPoints_defaultsToPort9042_multi() {
    assertThat(parseContactPoints("1.1.1.1:9143,2.2.2.2"))
      .containsExactly(
        new InetSocketAddress("1.1.1.1", 9143), new InetSocketAddress("2.2.2.2", 9042));
  }

  @Test public void contactPoints_hostAndPort() {
    assertThat(parseContactPoints("1.1.1.1:9142"))
      .containsExactly(new InetSocketAddress("1.1.1.1", 9142));
  }
}
