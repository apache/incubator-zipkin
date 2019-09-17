/*
 * Copyright 2015-2019 The OpenZipkin Authors
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
package zipkin2.server.internal;

import com.linecorp.armeria.common.ClosedSessionException;
import java.io.IOException;
import org.junit.Test;
import zipkin2.CheckResult;
import zipkin2.Component;

import static org.assertj.core.api.Assertions.assertThat;
import static zipkin2.server.internal.ZipkinHealthIndicator.ComponentHealthIndicator;

public class ZipkinHealthIndicatorTest {
  @Test public void addsMessageToDetails() {
    ComponentHealthIndicator healthIndicator = new ComponentHealthIndicator(new Component() {
      @Override public CheckResult check() {
        return CheckResult.failed(new IOException("socket disconnect"));
      }
    });

    assertThat(healthIndicator.health().getDetails())
      .containsEntry("error", "java.io.IOException: socket disconnect");
  }

  @Test public void doesntAddNullMessageToDetails() {
    ComponentHealthIndicator healthIndicator = new ComponentHealthIndicator(new Component() {
      @Override public CheckResult check() {
        return CheckResult.failed(ClosedSessionException.get());
      }
    });

    assertThat(healthIndicator.health().getDetails())
      .containsEntry("error", "com.linecorp.armeria.common.ClosedSessionException");
  }
}
