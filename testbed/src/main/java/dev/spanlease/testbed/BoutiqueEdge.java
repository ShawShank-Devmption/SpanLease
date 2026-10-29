package dev.spanlease.testbed;

import dev.spanlease.instrumentation.RootCaller;
import hipstershop.AdServiceGrpc;
import hipstershop.Demo;
import io.grpc.Context;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Driver-role edge: supplies root metadata and preserves each incoming store call's Context. */
final class BoutiqueEdge implements AutoCloseable {
  private final ExecutorService callbacks;
  private final Server server;

  private BoutiqueEdge(
      InetSocketAddress bind, RootCaller roots, ManagedChannel adsA, StoreTraffic traffic)
      throws IOException {
    AtomicInteger next = new AtomicInteger();
    callbacks =
        Executors.newCachedThreadPool(
            task -> {
              Thread thread = new Thread(task, "boutique-edge-" + next.incrementAndGet());
              thread.setDaemon(true);
              return thread;
            });
    server =
        NettyServerBuilder.forAddress(bind)
            .executor(callbacks)
            .addService(
                new AdServiceGrpc.AdServiceImplBase() {
                  @Override
                  public void getAds(
                      Demo.AdRequest request, StreamObserver<Demo.AdResponse> observer) {
                    traffic.forwarded();
                    try {
                      Demo.AdResponse response =
                          roots.call(
                              "ads-a",
                              AdServiceGrpc.newBlockingStub(adsA),
                              stub -> stub.getAds(request));
                      traffic.ok(response);
                      observer.onNext(response);
                      observer.onCompleted();
                    } catch (RuntimeException failure) {
                      Status status = Status.fromThrowable(failure);
                      if (status.getCode() == Status.Code.DEADLINE_EXCEEDED
                          || Context.current().getDeadline() != null
                              && Context.current().getDeadline().isExpired()) {
                        traffic.deadline();
                      } else {
                        traffic.failed();
                      }
                      observer.onError(status.asRuntimeException());
                    }
                  }
                })
            .build();
    try {
      server.start();
    } catch (IOException | RuntimeException failure) {
      server.shutdownNow();
      callbacks.shutdownNow();
      throw new IOException(
          "Boutique edge cannot bind " + bind.getHostString() + ":" + bind.getPort(), failure);
    }
  }

  static BoutiqueEdge start(
      InetSocketAddress bind, RootCaller roots, ManagedChannel adsA, StoreTraffic traffic)
      throws IOException {
    return new BoutiqueEdge(bind, roots, adsA, traffic);
  }

  int port() {
    return server.getPort();
  }

  @Override
  public void close() throws InterruptedException {
    server.shutdownNow();
    callbacks.shutdownNow();
    server.awaitTermination(5, TimeUnit.SECONDS);
    callbacks.awaitTermination(5, TimeUnit.SECONDS);
  }
}
