package dev.spanlease.testbed.feasibility;

import dev.spanlease.testbed.proto.ChainGrpc;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

final class FeasibilityHarness implements AutoCloseable {
  private final FeasibilityJournal journal;
  private final FeasibilityApplicationGate gate;
  private final ExecutorService callbacks;
  private final SdkTracerProvider provider;
  private final Server server;
  private final ManagedChannel channel;
  private final FeasibilityBlockingHelper helper;

  private FeasibilityHarness(
      FeasibilityJournal journal,
      FeasibilityApplicationGate gate,
      ExecutorService callbacks,
      SdkTracerProvider provider,
      Server server,
      ManagedChannel channel,
      FeasibilityBlockingHelper helper) {
    this.journal = journal;
    this.gate = gate;
    this.callbacks = callbacks;
    this.provider = provider;
    this.server = server;
    this.channel = channel;
    this.helper = helper;
  }

  static FeasibilityHarness start(
      int slots,
      int queueCapacity,
      FeasibilityChainService.HandlerBehavior behavior,
      boolean responseMetadata)
      throws IOException {
    FeasibilityJournal journal = new FeasibilityJournal();
    FeasibilityApplicationGate gate =
        new FeasibilityApplicationGate("svcA", slots, queueCapacity, journal);
    AtomicInteger callbackSequence = new AtomicInteger();
    ExecutorService callbacks =
        Executors.newFixedThreadPool(
            2,
            runnable ->
                new Thread(runnable, "bfeas-grpc-callback-" + callbackSequence.incrementAndGet()));
    SdkTracerProvider provider =
        SdkTracerProvider.builder().addSpanProcessor(new FeasibilitySpanProcessor(journal)).build();
    NettyServerBuilder serverBuilder =
        NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0))
            .executor(callbacks)
            .addStreamTracerFactory(FeasibilityServerHooks.arrivalTracerFactory(journal))
            .addService(new FeasibilityChainService(journal, gate, provider, behavior));
    if (responseMetadata) {
      serverBuilder.intercept(FeasibilityServerHooks.responseInterceptor(journal, "server@svcA"));
    }
    Server server = serverBuilder.build().start();
    ManagedChannel channel =
        NettyChannelBuilder.forAddress("127.0.0.1", server.getPort())
            .usePlaintext()
            .disableRetry()
            .build();
    return new FeasibilityHarness(
        journal,
        gate,
        callbacks,
        provider,
        server,
        channel,
        new FeasibilityBlockingHelper(journal, "client@probe"));
  }

  FeasibilityJournal journal() {
    return journal;
  }

  FeasibilityApplicationGate gate() {
    return gate;
  }

  FeasibilityBlockingHelper helper() {
    return helper;
  }

  ChainGrpc.ChainBlockingStub stub() {
    return ChainGrpc.newBlockingStub(channel);
  }

  ManagedChannel channel() {
    return channel;
  }

  @Override
  public void close() throws InterruptedException {
    channel.shutdownNow();
    server.shutdownNow();
    helper.close();
    gate.close();
    callbacks.shutdownNow();
    provider.close();
    if (!channel.awaitTermination(2, TimeUnit.SECONDS)
        || !server.awaitTermination(2, TimeUnit.SECONDS)
        || !callbacks.awaitTermination(2, TimeUnit.SECONDS)) {
      throw new IllegalStateException("feasibility server resources did not terminate");
    }
  }
}
