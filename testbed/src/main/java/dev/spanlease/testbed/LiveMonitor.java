package dev.spanlease.testbed;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.spanlease.common.ContractJson;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Loopback operator/demo monitor. Browser state is an output and never detector evidence. */
public final class LiveMonitor implements AutoCloseable {
  private final HttpServer server;
  private final ExecutorService http = Executors.newFixedThreadPool(4);
  private final ExecutorService runner = Executors.newSingleThreadExecutor();
  private final MonitorState state = new MonitorState(2_000);
  private final String token = UUID.randomUUID().toString().replace("-", "");
  private final Path runsDirectory;
  private final InetSocketAddress edgeBind;
  private volatile MonitorRun activeRun;

  private LiveMonitor(int port, Path runsDirectory, InetSocketAddress edgeBind) throws IOException {
    this.runsDirectory = runsDirectory;
    this.edgeBind = edgeBind;
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 16);
    server.createContext("/", this::handle);
    server.setExecutor(http);
    server.start();
  }

  public static LiveMonitor start(int port, Path runsDirectory) throws IOException {
    return start(port, runsDirectory, new InetSocketAddress("127.0.0.1", 9555));
  }

  static LiveMonitor start(int port, Path runsDirectory, InetSocketAddress edgeBind)
      throws IOException {
    return new LiveMonitor(port, runsDirectory, edgeBind);
  }

  public String url() {
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  private void handle(HttpExchange exchange) throws IOException {
    try {
      String host = exchange.getRequestHeaders().getFirst("Host");
      String local = "127.0.0.1:" + server.getAddress().getPort();
      String localhost = "localhost:" + server.getAddress().getPort();
      if (!local.equals(host) && !localhost.equals(host)) {
        respond(exchange, 403, "text/plain", "invalid host");
        return;
      }
      String path = exchange.getRequestURI().getPath();
      if (path.equals("/api/run")) {
        run(exchange);
        return;
      }
      if (path.equals("/api/inject")) {
        inject(exchange);
        return;
      }
      if (!exchange.getRequestMethod().equals("GET")) {
        respond(exchange, 405, "text/plain", "GET required");
        return;
      }
      if (path.equals("/api/state")) {
        respond(
            exchange,
            200,
            "application/json",
            ContractJson.mapper().writeValueAsString(state.snapshot()));
        return;
      }
      String resource =
          Map.of("/", "index.html", "/monitor.css", "monitor.css", "/monitor.js", "monitor.js")
              .get(path);
      if (resource == null) {
        respond(exchange, 404, "text/plain", "not found");
        return;
      }
      try (var stream = LiveMonitor.class.getResourceAsStream("/monitor/" + resource)) {
        if (stream == null) {
          respond(exchange, 500, "text/plain", "monitor resource missing");
          return;
        }
        String content =
            new String(stream.readAllBytes(), StandardCharsets.UTF_8)
                .replace("CONTROL_TOKEN", token);
        String type =
            resource.endsWith(".js")
                ? "text/javascript"
                : resource.endsWith(".css") ? "text/css" : "text/html";
        respond(exchange, 200, type, content);
      }
    } finally {
      exchange.close();
    }
  }

  private boolean authorized(HttpExchange exchange) throws IOException {
    if (!exchange.getRequestMethod().equals("POST")) {
      respond(exchange, 405, "text/plain", "POST required");
      return false;
    }
    String supplied = exchange.getRequestHeaders().getFirst("X-SpanLease-Control");
    String origin = exchange.getRequestHeaders().getFirst("Origin");
    if (supplied == null
        || !MessageDigest.isEqual(
            token.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8))
        || origin != null
            && !origin.equals(url())
            && !origin.equals("http://localhost:" + server.getAddress().getPort())) {
      respond(exchange, 403, "text/plain", "local control token and same origin required");
      return false;
    }
    return true;
  }

  private void inject(HttpExchange exchange) throws IOException {
    if (!authorized(exchange)) return;
    MonitorRun run = activeRun;
    if (run == null || !run.inject()) {
      respond(exchange, 409, "text/plain", "no running injectable workload");
      return;
    }
    respond(exchange, 200, "application/json", "{\"injected\":true}");
  }

  private void run(HttpExchange exchange) throws IOException {
    if (!authorized(exchange)) return;
    String query = exchange.getRequestURI().getRawQuery();
    String selected =
        query != null && query.startsWith("scenario=") && !query.contains("&")
            ? URLDecoder.decode(query.substring("scenario=".length()), StandardCharsets.UTF_8)
            : "";
    Scenario scenario;
    try {
      scenario = Scenario.named(selected);
    } catch (IllegalArgumentException unknown) {
      respond(exchange, 400, "text/plain", "unknown scenario");
      return;
    }
    RunPlan plan = RunPlan.create(scenario, System.currentTimeMillis());
    if (!state.begin(plan)) {
      respond(exchange, 409, "text/plain", "a run is already active");
      return;
    }
    runner.submit(
        () -> {
          try {
            try (MonitorRun live = MonitorRun.start(plan, runsDirectory, state, edgeBind)) {
              activeRun = live;
              live.awaitExit();
            } finally {
              activeRun = null;
            }
          } catch (Exception failure) {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            state.fail(failure.getClass().getSimpleName() + ": " + failure.getMessage());
          }
        });
    respond(exchange, 202, "application/json", "{\"accepted\":true}");
  }

  private static void respond(HttpExchange exchange, int status, String type, String body)
      throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", type + "; charset=utf-8");
    exchange.getResponseHeaders().set("Cache-Control", "no-store");
    exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
    exchange
        .getResponseHeaders()
        .set(
            "Content-Security-Policy",
            "default-src 'self'; style-src 'self' 'unsafe-inline'; frame-ancestors 'none'; base-uri 'none'");
    exchange.sendResponseHeaders(status, bytes.length);
    exchange.getResponseBody().write(bytes);
  }

  @Override
  public void close() throws InterruptedException {
    server.stop(0);
    runner.shutdownNow();
    MonitorRun live = activeRun;
    if (live != null) live.close();
    http.shutdownNow();
    runner.awaitTermination(15, TimeUnit.SECONDS);
    http.awaitTermination(5, TimeUnit.SECONDS);
  }

  public static void main(String[] args) throws Exception {
    int port = 8088;
    InetSocketAddress bind = new InetSocketAddress("127.0.0.1", 9555);
    for (int index = 0; index < args.length; index++) {
      if (args[index].equals("--edge-bind") && index + 1 < args.length) {
        bind = MonitorApplication.edgeBind(args[++index]);
      } else if (index == 0 && !args[index].startsWith("--")) {
        port = Integer.parseInt(args[index]);
      } else {
        throw new IllegalArgumentException("usage: [monitor-port] [--edge-bind host:port]");
      }
    }
    LiveMonitor monitor = start(port, Path.of("monitor-runs"), bind);
    Runtime.getRuntime()
        .addShutdownHook(
            new Thread(
                () -> {
                  try {
                    monitor.close();
                  } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                  }
                }));
    System.out.println("MONITOR " + monitor.url());
    new CountDownLatch(1).await();
  }
}
