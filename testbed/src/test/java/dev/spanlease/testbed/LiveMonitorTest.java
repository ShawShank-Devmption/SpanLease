package dev.spanlease.testbed;

import static org.assertj.core.api.Assertions.assertThat;

import dev.spanlease.common.ContractJson;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LiveMonitorTest {
  @TempDir Path runs;

  @Test
  void readOnlyStateIsLiveAndNeverCached() throws Exception {
    try (LiveMonitor monitor = LiveMonitor.start(0, runs)) {
      HttpResponse<String> response = send(monitor, "/api/state", null, null, "GET");
      assertThat(response.statusCode()).isEqualTo(200);
      assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
      assertThat(ContractJson.mapper().readTree(response.body()).get("phase").asText())
          .isEqualTo("IDLE");
    }
  }

  @Test
  void workloadControlRequiresTokenAndSameOrigin() throws Exception {
    try (LiveMonitor monitor = LiveMonitor.start(0, runs)) {
      assertThat(send(monitor, "/api/run?scenario=closed-ring", null, null, "POST").statusCode())
          .isEqualTo(403);
      String html = send(monitor, "/", null, null, "GET").body();
      var matcher = Pattern.compile("name=\"control-token\" content=\"([a-f0-9]+)\"").matcher(html);
      assertThat(matcher.find()).isTrue();
      String token = matcher.group(1);
      assertThat(
              send(
                      monitor,
                      "/api/run?scenario=closed-ring",
                      token,
                      "https://untrusted.example",
                      "POST")
                  .statusCode())
          .isEqualTo(403);
      assertThat(send(monitor, "/api/run?scenario=typo", token, monitor.url(), "POST").statusCode())
          .isEqualTo(400);
      assertThat(
              send(monitor, "/api/run?scenario=closed-ring", token, monitor.url(), "GET")
                  .statusCode())
          .isEqualTo(405);
      assertThat(send(monitor, "/unknown", null, null, "GET").statusCode()).isEqualTo(404);
      assertThat(
              ContractJson.mapper()
                  .readTree(send(monitor, "/api/state", null, null, "GET").body())
                  .get("phase")
                  .asText())
          .isEqualTo("IDLE");
    }
  }

  private static HttpResponse<String> send(
      LiveMonitor monitor, String path, String token, String origin, String method)
      throws Exception {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create(monitor.url() + path))
            .method(method, HttpRequest.BodyPublishers.noBody());
    if (token != null) request.header("X-SpanLease-Control", token);
    if (origin != null) request.header("Origin", origin);
    return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
  }
}
