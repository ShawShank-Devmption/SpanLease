package dev.spanlease.testbed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import hipstershop.AdServiceGrpc;
import hipstershop.Demo;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(60)
class BoutiqueDeploymentTest {
  @Test
  void edgeForwardsStoreCallsToAdsA() throws Exception {
    try (var deployment = start(Scenario.boutiqueLive())) {
      ManagedChannel channel = channel(deployment.edgePort());
      try {
        for (int index = 0; index < 20; index++) assertKitchen(stub(channel).getAds(kitchen()));
        assertThat(deployment.store(System.currentTimeMillis()).ok()).isEqualTo(20);
      } finally {
        channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
      }
    }
  }

  @Test
  void injectDeadlocksAdsAAndStoreCallsTimeOut() throws Exception {
    try (var deployment = start(Scenario.boutiqueDeadlock())) {
      ManagedChannel channel = channel(deployment.edgePort());
      try {
        // Establish the transport before measuring frontend-sized deadlines. A cold
        // channel may time out before the edge receives a call on a loaded CI host.
        assertKitchen(
            AdServiceGrpc.newBlockingStub(channel)
                .withDeadlineAfter(5, TimeUnit.SECONDS)
                .getAds(kitchen()));
        var fault = deployment.inject();
        assertThatThrownBy(() -> fault.get(3, TimeUnit.SECONDS))
            .isInstanceOf(TimeoutException.class);
        for (int index = 0; index < 10; index++) {
          assertThatThrownBy(() -> stub(channel).getAds(kitchen()))
              .isInstanceOfSatisfying(
                  StatusRuntimeException.class,
                  failure ->
                      assertThat(failure.getStatus().getCode())
                          .isEqualTo(Status.Code.DEADLINE_EXCEEDED));
        }
        assertThat(awaitDeadlines(deployment).deadlineExceeded()).isGreaterThanOrEqualTo(10);
        assertThatThrownBy(deployment::inject).isInstanceOf(IllegalStateException.class);
      } finally {
        channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
      }
    }
  }

  @Test
  void spareInjectionCompletesAndStoreRecovers() throws Exception {
    try (var deployment = start(Scenario.boutiqueSpareCapacity())) {
      assertKitchen(deployment.inject().get(20, TimeUnit.SECONDS));
      ManagedChannel channel = channel(deployment.edgePort());
      try {
        assertKitchen(stub(channel).getAds(kitchen()));
      } finally {
        channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
      }
    }
  }

  @Test
  void liveModeRejectsInject() throws Exception {
    try (var deployment = start(Scenario.boutiqueLive())) {
      assertThatThrownBy(deployment::inject).isInstanceOf(IllegalStateException.class);
    }
  }

  @Test
  void edgePortConflictFailsFast() throws Exception {
    try (ServerSocket occupied = new ServerSocket()) {
      occupied.bind(new InetSocketAddress("127.0.0.1", 0));
      RunPlan plan = RunPlan.create(Scenario.boutiqueLive(), System.currentTimeMillis());
      assertThatThrownBy(
              () ->
                  BoutiqueDeployment.start(
                      plan,
                      "http://127.0.0.1:9",
                      new InetSocketAddress("127.0.0.1", occupied.getLocalPort()),
                      42,
                      new RuntimeDiagnostics()))
          .isInstanceOf(IOException.class)
          .hasMessageContaining("" + occupied.getLocalPort());
    }
  }

  private static BoutiqueDeployment start(Scenario scenario) throws Exception {
    return BoutiqueDeployment.start(
        RunPlan.create(scenario, System.currentTimeMillis()),
        "http://127.0.0.1:9",
        new InetSocketAddress("127.0.0.1", 0),
        42,
        new RuntimeDiagnostics());
  }

  private static StoreStats awaitDeadlines(BoutiqueDeployment deployment) throws Exception {
    var result = new CompletableFuture<StoreStats>();
    try (var sampler = Executors.newSingleThreadScheduledExecutor()) {
      sampler.scheduleAtFixedRate(
          () -> {
            StoreStats sample = deployment.store(System.currentTimeMillis());
            if (sample.deadlineExceeded() >= 10) result.complete(sample);
          },
          0,
          10,
          TimeUnit.MILLISECONDS);
      return result.get(5, TimeUnit.SECONDS);
    }
  }

  private static ManagedChannel channel(int port) {
    return NettyChannelBuilder.forAddress("127.0.0.1", port).usePlaintext().disableRetry().build();
  }

  private static AdServiceGrpc.AdServiceBlockingStub stub(ManagedChannel channel) {
    return AdServiceGrpc.newBlockingStub(channel).withDeadlineAfter(100, TimeUnit.MILLISECONDS);
  }

  private static Demo.AdRequest kitchen() {
    return Demo.AdRequest.newBuilder().addContextKeys("kitchen").build();
  }

  private static void assertKitchen(Demo.AdResponse response) {
    assertThat(response.getAdsList())
        .extracting(Demo.Ad::getText)
        .containsExactly(
            "Bamboo glass jar for sale. 10% off.", "Mug for sale. Buy two, get third one for free");
  }
}
