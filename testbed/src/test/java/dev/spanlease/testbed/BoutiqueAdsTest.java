package dev.spanlease.testbed;

import static org.assertj.core.api.Assertions.assertThat;

import hipstershop.Demo;
import java.util.Random;
import org.junit.jupiter.api.Test;

class BoutiqueAdsTest {
  @Test
  void kitchenReturnsTheOriginalGoogleAds() {
    var ads = new BoutiqueAds(new Random(42));
    var response = ads.getAds(Demo.AdRequest.newBuilder().addContextKeys("kitchen").build());
    assertThat(response.getAdsList())
        .extracting(Demo.Ad::getRedirectUrl)
        .containsExactly("/product/0PUK6V6EV0", "/product/6E92ZMYYFZ");
    assertThat(response.getAdsList())
        .extracting(Demo.Ad::getText)
        .containsExactly(
            "Bamboo glass jar for sale. 10% off.", "Mug for sale. Buy two, get third one for free");
  }

  @Test
  void repeatedKeysPreserveRepeatedMatchesAndUnknownKeysDoNotReplaceKnownMatches() {
    var ads = new BoutiqueAds(new Random(42));
    assertThat(
            ads.getAds(
                    Demo.AdRequest.newBuilder()
                        .addContextKeys("kitchen")
                        .addContextKeys("kitchen")
                        .addContextKeys("unknown")
                        .build())
                .getAdsList())
        .extracting(Demo.Ad::getRedirectUrl)
        .containsExactly(
            "/product/0PUK6V6EV0",
            "/product/6E92ZMYYFZ",
            "/product/0PUK6V6EV0",
            "/product/6E92ZMYYFZ");
  }

  @Test
  void emptyAndUnknownContextsUseTwoIndependentCatalogDrawsWithReplacement() {
    Random firstOnly =
        new Random() {
          @Override
          public int nextInt(int bound) {
            return 0;
          }
        };
    var ads = new BoutiqueAds(firstOnly);
    for (var request :
        java.util.List.of(
            Demo.AdRequest.getDefaultInstance(),
            Demo.AdRequest.newBuilder().addContextKeys("unknown").build())) {
      assertThat(ads.getAds(request).getAdsList())
          .extracting(Demo.Ad::getRedirectUrl)
          .containsExactly("/product/66VCHSJNUP", "/product/66VCHSJNUP");
    }
  }
}
