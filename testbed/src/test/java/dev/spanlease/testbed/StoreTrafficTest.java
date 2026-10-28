package dev.spanlease.testbed;

import static org.assertj.core.api.Assertions.assertThat;

import hipstershop.Demo;
import org.junit.jupiter.api.Test;

class StoreTrafficTest {
  @Test
  void keepsCountsAndOnlyFiveRecentAds() {
    StoreTraffic traffic = new StoreTraffic();
    for (int index = 0; index < 7; index++) {
      traffic.forwarded();
      traffic.ok(
          Demo.AdResponse.newBuilder()
              .addAds(Demo.Ad.newBuilder().setText("ad-" + index).setRedirectUrl("/" + index))
              .build());
    }
    traffic.deadline();
    traffic.failed();
    StoreStats sample = traffic.sample("http://localhost:8080", 9555, 1);
    assertThat(sample.forwarded()).isEqualTo(7);
    assertThat(sample.ok()).isEqualTo(7);
    assertThat(sample.deadlineExceeded()).isEqualTo(1);
    assertThat(sample.failed()).isEqualTo(1);
    assertThat(sample.recentAds())
        .extracting(StoreStats.AdView::text)
        .containsExactly("ad-2", "ad-3", "ad-4", "ad-5", "ad-6");
  }
}
