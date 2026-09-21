package dev.spanlease.testbed;

import static org.assertj.core.api.Assertions.assertThat;

import dev.spanlease.testbed.proto.CallReply;
import dev.spanlease.testbed.proto.CallRequest;
import dev.spanlease.testbed.proto.ChainGrpc;
import dev.spanlease.testbed.proto.Hop;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;
import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Build compatibility only: this echo service is not the bounded application testbed. */
class GrpcCompatibilityTest {
  @Test
  @Timeout(20)
  void generatedBlockingStubCallsRealLoopbackServer() throws Exception {
    Server server =
        NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0))
            .addService(
                new ChainGrpc.ChainImplBase() {
                  @Override
                  public void call(CallRequest request, StreamObserver<CallReply> observer) {
                    observer.onNext(
                        CallReply.newBuilder()
                            .setRequestId(request.getRequestId())
                            .setHopsCompleted(request.getRemainingCount())
                            .build());
                    observer.onCompleted();
                  }
                })
            .build()
            .start();
    ManagedChannel channel = null;
    try {
      channel =
          NettyChannelBuilder.forAddress("127.0.0.1", server.getPort())
              .usePlaintext()
              .disableRetry()
              .build();
      CallReply reply =
          ChainGrpc.newBlockingStub(channel)
              .withDeadlineAfter(5, TimeUnit.SECONDS)
              .call(
                  CallRequest.newBuilder()
                      .setRequestId("bootstrap-42")
                      .addRemaining(Hop.newBuilder().setTargetService("svcA"))
                      .build());
      assertThat(reply.getRequestId()).isEqualTo("bootstrap-42");
      assertThat(reply.getHopsCompleted()).isEqualTo(1);
    } finally {
      if (channel != null) {
        channel.shutdownNow();
      }
      server.shutdownNow();
      if (channel != null) {
        assertThat(channel.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
      }
      assertThat(server.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }
  }
}
