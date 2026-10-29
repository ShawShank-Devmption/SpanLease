package dev.spanlease.testbed;

import dev.spanlease.instrumentation.RootCaller;
import hipstershop.AdServiceGrpc;
import hipstershop.Demo;
import io.grpc.Context;
import io.grpc.ManagedChannel;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.stub.MetadataUtils;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** One Boutique run with independent store roots and a one-shot, isolated diagnostic root. */
final class BoutiqueDeployment implements AutoCloseable {
  private final Scenario scenario;
  private final StoreTraffic traffic = new StoreTraffic();
  private final TelemetryPipelines telemetry = new TelemetryPipelines();
  private final Map<String, BoutiqueAdNode> nodes = new LinkedHashMap<>();
  private final ExecutorService faultThread =
      Executors.newSingleThreadExecutor(
          task -> {
            Thread thread = new Thread(task, "boutique-fault-root");
            thread.setDaemon(true);
            return thread;
          });
  private final Context.CancellableContext faultContext = Context.ROOT.withCancellation();
  private RootInstrumentation root;
  private RootCaller roots;
  private ManagedChannel adsA;
  private BoutiqueEdge edge;
  private boolean injected;
  private boolean closed;

  private BoutiqueDeployment(Scenario scenario) {
    this.scenario = scenario;
  }

  static BoutiqueDeployment start(
      RunPlan plan,
      String endpoint,
      InetSocketAddress edgeBind,
      long seed,
      RuntimeDiagnostics diagnostics)
      throws IOException, InterruptedException {
    if (plan.scenario().application() != Scenario.Application.BOUTIQUE) {
      throw new IllegalArgumentException("Boutique deployment requires a Boutique scenario");
    }
    BoutiqueDeployment deployment = new BoutiqueDeployment(plan.scenario());
    try {
      HandlerJournal journal = new HandlerJournal(Clock.systemUTC());
      Map<String, Integer> ports = new LinkedHashMap<>();
      List<String> services = List.of("ads-a", "ads-b", "ads-c");
      for (int index = 0; index < services.size(); index++) {
        String service = services.get(index);
        String instance = plan.instanceByService().get(service);
        BoutiqueAdNode node =
            BoutiqueAdNode.start(
                service,
                instance,
                plan.scenario().capacities().get(service),
                plan.manifest().parameters(),
                deployment.telemetry.open(endpoint, instance).sink(),
                () -> UUID.randomUUID().toString(),
                diagnostics,
                journal,
                plan.scenario().injectable(),
                services.get((index + 1) % services.size()),
                index == 0 ? plan.scenario().rootHops().getLast().getWorkMs() : 0,
                new Random(seed + index));
        deployment.nodes.put(service, node);
        ports.put(service, node.port());
      }
      deployment.nodes.values().forEach(node -> node.connect(ports));
      deployment.root =
          new RootInstrumentation(
              plan.driverInstanceId(),
              plan.manifest().parameters(),
              deployment.telemetry.open(endpoint, plan.driverInstanceId()).sink(),
              () -> UUID.randomUUID().toString());
      deployment.roots = deployment.root.caller();
      deployment.adsA =
          NettyChannelBuilder.forAddress("127.0.0.1", ports.get("ads-a"))
              .usePlaintext()
              .disableRetry()
              .build();
      deployment.edge =
          BoutiqueEdge.start(edgeBind, deployment.roots, deployment.adsA, deployment.traffic);
      return deployment;
    } catch (IOException | RuntimeException | InterruptedException failure) {
      try {
        deployment.close();
      } catch (Exception cleanup) {
        failure.addSuppressed(cleanup);
      }
      throw failure;
    }
  }

  StoreStats store(long nowMs) {
    return traffic.sample("http://localhost:8080", edgePort(), nowMs);
  }

  int edgePort() {
    return edge.port();
  }

  synchronized CompletableFuture<Demo.AdResponse> inject() {
    if (!scenario.injectable() || injected || closed) {
      throw new IllegalStateException("this run cannot inject a fault");
    }
    injected = true;
    return CompletableFuture.supplyAsync(
        () -> {
          Context previous = faultContext.attach();
          try {
            var stub =
                AdServiceGrpc.newBlockingStub(adsA)
                    .withInterceptors(
                        MetadataUtils.newAttachHeadersInterceptor(BoutiqueFaults.headers(3)));
            if (!scenario.rootWithoutDeadline()) {
              stub =
                  stub.withDeadlineAfter(
                      Scenario.DEFAULT_ROOT_DEADLINE.toMillis(), TimeUnit.MILLISECONDS);
            }
            Demo.AdRequest request = Demo.AdRequest.newBuilder().addContextKeys("kitchen").build();
            return roots.call("ads-a", stub, client -> client.getAds(request));
          } finally {
            faultContext.detach(previous);
          }
        },
        faultThread);
  }

  @Override
  public void close() throws InterruptedException {
    synchronized (this) {
      if (closed) return;
      closed = true;
    }
    faultContext.cancel(null);
    if (edge != null) edge.close();
    if (adsA != null) adsA.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    faultThread.shutdownNow();
    faultThread.awaitTermination(5, TimeUnit.SECONDS);
    for (BoutiqueAdNode node : nodes.values()) node.close();
    if (root != null) root.close();
    telemetry.close();
  }
}
