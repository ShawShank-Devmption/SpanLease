package dev.spanlease.testbed;

import dev.spanlease.common.RunManifest;
import dev.spanlease.instrumentation.EventSink;
import dev.spanlease.instrumentation.InstanceRecorder;
import dev.spanlease.instrumentation.LeasePolicy;
import dev.spanlease.instrumentation.PeriodicScanner;
import dev.spanlease.instrumentation.SlotTrackingExecutor;
import dev.spanlease.instrumentation.SpanLeaseClient;
import dev.spanlease.instrumentation.SpanLeaseServer;
import hipstershop.AdServiceGrpc;
import hipstershop.Demo;
import io.grpc.Context;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.ServerInterceptors;
import io.grpc.Status;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.MetadataUtils;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.random.RandomGenerator;

/** Native Boutique unary adapter. Only admitted jobs own slots; diagnostic forwarding is opt-in. */
final class BoutiqueAdNode implements AutoCloseable {
  private final String service;
  private final String nextService;
  private final long leafWorkMs;
  private final BoutiqueAds ads;
  private final RuntimeDiagnostics diagnostics;
  private final HandlerJournal journal;
  private final InstanceRecorder recorder;
  private final SlotTrackingExecutor gate;
  private final SpanLeaseClient client;
  private final SpanLeaseServer hooks;
  private final PeriodicScanner scanner;
  private final ExecutorService callbacks;
  private final Map<String, ManagedChannel> peers = new ConcurrentHashMap<>();
  private final Server server;

  private BoutiqueAdNode(
      String service,
      String instance,
      int capacity,
      RunManifest.Parameters parameters,
      EventSink sink,
      Supplier<String> ids,
      RuntimeDiagnostics diagnostics,
      HandlerJournal journal,
      boolean faultEnabled,
      String nextService,
      long leafWorkMs,
      RandomGenerator random)
      throws IOException, InterruptedException {
    if (leafWorkMs < 0 || leafWorkMs > 600_000)
      throw new IllegalArgumentException("invalid diagnostic work duration");
    this.service = service;
    this.nextService = nextService;
    this.leafWorkMs = leafWorkMs;
    this.diagnostics = diagnostics;
    this.journal = journal;
    ads = new BoutiqueAds(random);
    recorder = new InstanceRecorder(instance, sink, Clock.systemUTC(), System::nanoTime);
    gate =
        new SlotTrackingExecutor(
            recorder, RunPlan.POOL, capacity, 4_096, threads(service + "-worker"));
    client = new SpanLeaseClient(gate, ids);
    hooks = new SpanLeaseServer(gate, ids);
    LeasePolicy policy =
        new LeasePolicy(
            parameters.leaseAgeThresholdMs(),
            LeasePolicy.DEFAULT.renewalMs(),
            LeasePolicy.DEFAULT.expiryFactor());
    scanner =
        new PeriodicScanner(
            recorder,
            List.of(gate),
            policy,
            Duration.ofMillis(Math.min(policy.ageThresholdMs(), policy.renewalMs()) / 2),
            Duration.ofMillis(parameters.checkpointIntervalMs()),
            service + "-scanner");
    callbacks = Executors.newFixedThreadPool(2, threads(service + "-grpc-callback"));
    server =
        NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0))
            .executor(callbacks)
            .addStreamTracerFactory(hooks.streamTracerFactory())
            .addService(
                hooks.intercept(
                    () ->
                        ServerInterceptors.intercept(
                            new NativeAds(), BoutiqueFaults.interceptor(faultEnabled))))
            .build();
    try {
      server.start();
    } catch (IOException | RuntimeException failure) {
      server.shutdownNow();
      callbacks.shutdownNow();
      scanner.close();
      gate.close();
      throw failure;
    }
  }

  static BoutiqueAdNode start(
      String service,
      String instance,
      int capacity,
      RunManifest.Parameters parameters,
      EventSink sink,
      Supplier<String> ids,
      RuntimeDiagnostics diagnostics,
      HandlerJournal journal,
      boolean faultEnabled,
      String nextService,
      long leafWorkMs,
      RandomGenerator random)
      throws IOException, InterruptedException {
    return new BoutiqueAdNode(
        service,
        instance,
        capacity,
        parameters,
        sink,
        ids,
        diagnostics,
        journal,
        faultEnabled,
        nextService,
        leafWorkMs,
        random);
  }

  int port() {
    return server.getPort();
  }

  SlotTrackingExecutor.Snapshot snapshot() {
    return gate.snapshot();
  }

  void connect(Map<String, Integer> ports) {
    ports.forEach(
        (name, port) ->
            peers.computeIfAbsent(
                name,
                ignored ->
                    NettyChannelBuilder.forAddress("127.0.0.1", port)
                        .usePlaintext()
                        .disableRetry()
                        .build()));
  }

  private Demo.AdResponse handle(
      SlotTrackingExecutor.ExecutionContext execution, Demo.AdRequest request, int budget) {
    diagnostics.enter(service, execution);
    journal.record(service, "boutique-root-1", budget + 1, HandlerJournal.Boundary.RUN);
    try {
      if (budget > 0) {
        ManagedChannel peer = peers.get(nextService);
        if (peer == null)
          throw Status.FAILED_PRECONDITION
              .withDescription("no fixed diagnostic peer for " + service)
              .asRuntimeException();
        var stub =
            AdServiceGrpc.newBlockingStub(peer)
                .withInterceptors(
                    MetadataUtils.newAttachHeadersInterceptor(BoutiqueFaults.headers(budget - 1)));
        journal.record(service, "boutique-root-1", budget + 1, HandlerJournal.Boundary.CALL_ENTER);
        try {
          return client.call(
              execution, nextService, stub, downstream -> downstream.getAds(request));
        } finally {
          journal.record(
              service, "boutique-root-1", budget + 1, HandlerJournal.Boundary.CALL_RETURN);
        }
      }
      // Finite fault-control work, not synchronization or stock Boutique latency.
      long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(leafWorkMs);
      for (long remaining = leafWorkMs;
          remaining > 0;
          remaining = TimeUnit.NANOSECONDS.toMillis(until - System.nanoTime())) {
        if (Context.current().isCancelled()) throw Status.CANCELLED.asRuntimeException();
        try {
          Thread.sleep(Math.min(remaining, 20));
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          throw Status.CANCELLED.asRuntimeException();
        }
      }
      return ads.getAds(request);
    } finally {
      journal.record(
          service, "boutique-root-1", budget + 1, HandlerJournal.Boundary.HANDLER_RETURN);
      diagnostics.exit(execution.executionId());
    }
  }

  private final class NativeAds extends AdServiceGrpc.AdServiceImplBase {
    @Override
    public void getAds(Demo.AdRequest request, StreamObserver<Demo.AdResponse> observer) {
      int budget = BoutiqueFaults.REMAINING.get();
      hooks.handleUnary(request, observer, (execution, body) -> handle(execution, body, budget));
    }
  }

  @Override
  public void close() throws InterruptedException {
    server.shutdownNow();
    peers.values().forEach(ManagedChannel::shutdownNow);
    gate.close();
    recorder.checkpoint();
    scanner.close();
    server.awaitTermination(5, TimeUnit.SECONDS);
    for (var channel : peers.values()) channel.awaitTermination(5, TimeUnit.SECONDS);
    callbacks.shutdown();
    callbacks.awaitTermination(5, TimeUnit.SECONDS);
  }

  private static ThreadFactory threads(String prefix) {
    AtomicInteger number = new AtomicInteger();
    return runnable -> {
      Thread thread = new Thread(runnable, prefix + "-" + number.incrementAndGet());
      thread.setDaemon(true);
      return thread;
    };
  }
}
