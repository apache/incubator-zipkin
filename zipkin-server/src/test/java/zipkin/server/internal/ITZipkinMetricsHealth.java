/**
 * Copyright 2015-2018 The OpenZipkin Authors
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
package zipkin.server.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;
import zipkin.Codec;
import zipkin.Span;
import zipkin.server.ZipkinServer;
import zipkin2.storage.InMemoryStorage;

import static java.util.Arrays.asList;
import static org.assertj.core.api.Assertions.assertThat;
import static zipkin.TestObjects.LOTS_OF_SPANS;

@SpringBootTest(
  classes = ZipkinServer.class,
  webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
  properties = "spring.config.name=zipkin-server"
)
@RunWith(SpringRunner.class)
public class ITZipkinMetricsHealth {

  @Autowired InMemoryStorage storage;
  @Autowired MeterRegistry registry;
  @Value("${local.server.port}") int zipkinPort;

  OkHttpClient client = new OkHttpClient.Builder().followRedirects(true).build();

  static Double readDouble(String json, String jsonPath) {
    return JsonPath.compile(jsonPath).read(json);
  }

  static String readString(String json, String jsonPath) {
    return JsonPath.compile(jsonPath).read(json);
  }

  static List readJson(String json) throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    JsonNode jsonNode = mapper.readTree(json);
    List<String> fieldsList = new ArrayList<>();
    jsonNode.fieldNames().forEachRemaining(fieldsList::add);
    return fieldsList;
  }

  @Before public void init() {
    storage.clear();
  }

  @Test public void healthIsOK() throws Exception {
    assertThat(get("/health").isSuccessful())
      .isTrue();

    // ensure we don't track health in prometheus
    assertThat(getAsString("/prometheus"))
      .doesNotContain("health");
  }

  @Test public void metricsIsOK() throws Exception {
    assertThat(get("/metrics").isSuccessful())
      .isTrue();

    // ensure we don't track metrics in prometheus
    assertThat(getAsString("/prometheus"))
      .doesNotContain("metrics");
  }

  @Test public void actuatorIsOK() throws Exception {
    assertThat(get("/actuator").isSuccessful())
      .isTrue();

    // ensure we don't track actuator in prometheus
    assertThat(getAsString("/prometheus"))
      .doesNotContain("actuator");
  }

  @Test public void prometheusIsOK() throws Exception {
    assertThat(get("/prometheus").isSuccessful())
      .isTrue();

    // ensure we don't track prometheus in prometheus
    assertThat(getAsString("/prometheus"))
      .doesNotContain("prometheus");
  }

  /** Makes sure the prometheus filter doesn't count twice */
  @Test public void writeSpans_updatesPrometheusMetrics() throws Exception {
    List<Span> spans = asList(LOTS_OF_SPANS[0], LOTS_OF_SPANS[1], LOTS_OF_SPANS[2]);
    byte[] body = Codec.JSON.writeSpans(spans);

    post("/api/v1/spans", body);
    post("/api/v1/spans", body);

    double messagesCount = registry.counter("zipkin_collector.spans", "transport", "http").count();
    // Get the http count from the registry and it should match the summation previous count
    // and count of calls below
    long httpCount = registry
      .find("http_request_duration")
      .tag("path", "/api/v1/spans")
      .timer()
      .count();

    String prometheus = getAsString("/prometheus");

    // ensure unscoped counter does not exist
    assertThat(prometheus)
      .doesNotContain("zipkin_collector_spans_total " + messagesCount);
    assertThat(prometheus)
      .contains("zipkin_collector_spans_total{transport=\"http\",} " + messagesCount);
    assertThat(prometheus)
      .contains(
        "http_request_duration_seconds_count{method=\"POST\",path=\"/api/v1/spans\",status=\"200\",} "
          + httpCount);
  }

  @Test public void writeSpans_updatesMetrics() throws Exception {
    List<Span> spans = asList(LOTS_OF_SPANS[0], LOTS_OF_SPANS[1], LOTS_OF_SPANS[2]);
    byte[] body = Codec.JSON.writeSpans(spans);
    double messagesCount =
      registry.counter("zipkin_collector.messages", "transport", "http").count();
    double bytesCount = registry.counter("zipkin_collector.bytes", "transport", "http").count();
    double spansCount = registry.counter("zipkin_collector.spans", "transport", "http").count();
    post("/api/v1/spans", body);
    post("/api/v1/spans", body);

    String json = getAsString("/metrics");

    assertThat(readDouble(json, "$.['counter.zipkin_collector.messages.http']"))
      .isEqualTo(messagesCount + 2.0);
    assertThat(readDouble(json, "$.['counter.zipkin_collector.bytes.http']"))
      .isEqualTo(bytesCount + (body.length * 2));
    assertThat(readDouble(json, "$.['gauge.zipkin_collector.message_bytes.http']"))
      .isEqualTo(body.length);
    assertThat(readDouble(json, "$.['counter.zipkin_collector.spans.http']"))
      .isEqualTo(spansCount + (spans.size() * 2));
    assertThat(readDouble(json, "$.['gauge.zipkin_collector.message_spans.http']"))
      .isEqualTo(spans.size());
  }

  @Test public void writeSpans_malformedUpdatesMetrics() throws Exception {
    byte[] body = {'h', 'e', 'l', 'l', 'o'};
    Double messagesCount =
      registry.counter("zipkin_collector.messages", "transport", "http").count();
    Double messagesDroppedCount =
      registry.counter("zipkin_collector.messages_dropped", "transport", "http").count();
    post("/api/v1/spans", body);

    String json = getAsString("/metrics");

    assertThat(readDouble(json, "$.['counter.zipkin_collector.messages.http']"))
      .isEqualTo(messagesCount + 1);
    assertThat(readDouble(json, "$.['counter.zipkin_collector.messages_dropped.http']"))
      .isEqualTo(messagesDroppedCount + 1);
  }

  @Test public void readsHealth() throws Exception {
    String json = getAsString("/health");
    assertThat(readString(json, "$.status"))
      .isIn("UP", "DOWN", "UNKNOWN");
    assertThat(readString(json, "$.zipkin.status"))
      .isIn("UP", "DOWN", "UNKNOWN");
  }

  @Test public void writesSpans_readMetricsFormat() throws Exception {
    byte[] span = {'z', 'i', 'p', 'k', 'i', 'n'};
    List<Span> spans = asList(LOTS_OF_SPANS[0], LOTS_OF_SPANS[1], LOTS_OF_SPANS[2]);
    byte[] body = Codec.JSON.writeSpans(spans);
    post("/api/v1/spans", body);
    post("/api/v1/spans", body);
    post("/api/v1/spans", span);
    Thread.sleep(1500);

    String metrics = getAsString("/metrics");

    assertThat(readJson(metrics))
      .containsExactlyInAnyOrder(
        "gauge.zipkin_collector.message_spans.http"
        , "gauge.zipkin_collector.message_bytes.http"
        , "counter.zipkin_collector.messages.http"
        , "counter.zipkin_collector.bytes.http"
        , "counter.zipkin_collector.spans.http"
        , "counter.zipkin_collector.messages_dropped.http"
        , "counter.zipkin_collector.spans_dropped.http"
      );
  }

  private String getAsString(String path) throws IOException {
    Response response = get(path);
    assertThat(response.isSuccessful()).isTrue();
    return response.body().string();
  }

  private Response get(String path) throws IOException {
    return client.newCall(new Request.Builder()
      .url("http://localhost:" + zipkinPort + path)
      .build()).execute();
  }

  private Response post(String path, byte[] body) throws IOException {
    return client.newCall(new Request.Builder()
      .url("http://localhost:" + zipkinPort + path)
      .post(RequestBody.create(null, body))
      .build()).execute();
  }
}
