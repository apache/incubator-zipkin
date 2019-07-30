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
package zipkin2.server.internal.elasticsearch;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.client.endpoint.EndpointGroupRegistry;
import com.linecorp.armeria.client.endpoint.StaticEndpointGroup;
import com.linecorp.armeria.client.endpoint.healthcheck.HttpHealthCheckedEndpointGroup;
import com.linecorp.armeria.client.endpoint.healthcheck.HttpHealthCheckedEndpointGroupBuilder;
import com.linecorp.armeria.common.SessionProtocol;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import zipkin2.elasticsearch.ElasticsearchStorage.LazyHttpClient;

import static com.linecorp.armeria.client.endpoint.EndpointSelectionStrategy.ROUND_ROBIN;

final class LazyHttpClientImpl implements LazyHttpClient {
  final HttpClientFactory factory;
  final SessionProtocol protocol;
  final Supplier<EndpointGroup> configuredEndpoints;

  volatile HttpClient result;

  LazyHttpClientImpl(HttpClientFactory factory, SessionProtocol protocol,
    Supplier<EndpointGroup> configuredEndpoints) {
    this.factory = factory;
    this.protocol = protocol;
    this.configuredEndpoints = configuredEndpoints;
  }

  @Override public void close() {
    EndpointGroup endpointGroup = EndpointGroupRegistry.get("elasticsearch");
    if (endpointGroup != null) {
      endpointGroup.close();
      EndpointGroupRegistry.unregister("elasticsearch");
    }
  }

  @Override public HttpClient get() {
    if (result == null) {
      synchronized (this) {
        if (result == null) {
          result = factory.apply(getEndpoint());
        }
      }
    }
    return result;
  }

  Endpoint getEndpoint() {
    EndpointGroup endpointGroup = configuredEndpoints.get();
    if (endpointGroup instanceof StaticEndpointGroup && endpointGroup.endpoints().size() == 1) {
      // Just one non-domain URL, can connect directly without enabling load balancing.
      return endpointGroup.endpoints().get(0);
    }

    // TODO: why must we do this instead of using direct type references.
    // The static factory is concerning.
    EndpointGroupRegistry.register("elasticsearch", endpointGroup, ROUND_ROBIN);
    return Endpoint.ofGroup("elasticsearch");
  }

  // Unused code until we can get to a point of stability. This must become optional if we add it.
  HttpHealthCheckedEndpointGroup decorateHealthCheck(EndpointGroup endpointGroup) {
    HttpHealthCheckedEndpointGroup healthChecked =
      new HttpHealthCheckedEndpointGroupBuilder(endpointGroup, "/_cluster/health")
        .protocol(protocol)
        .clientFactory(factory.delegate)
        .withClientOptions(factory::configureOptionsExceptLogging)
        .build();

    // Since we aren't holding up server startup, or sitting on the event loop, it is ok to block.
    // The alternative is round-robin, which could be unlucky and hit a bad node first.
    //
    // We are blocking a second as this should be enough time for a health check to respond
    // TODO: I don't know if this is enough time considering amazon and it is really too fragile
    try {
      healthChecked.awaitInitialEndpoints(1, TimeUnit.SECONDS);
    } catch (Exception e) {
      healthChecked.close(); // we'll recreate it the next time around.
      throw new IllegalStateException("couldn't connect any of " + endpointGroup.endpoints(), e);
    }
    return healthChecked;
  }

  @Override public final String toString() {
    return configuredEndpoints.toString();
  }
}
