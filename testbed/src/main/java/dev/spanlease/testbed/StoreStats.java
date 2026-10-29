package dev.spanlease.testbed;

import java.util.List;
import java.util.Objects;

/** Bounded operator-only summary of live Online Boutique Ad Service traffic. */
public record StoreStats(
    String storeUrl,
    int edgePort,
    long forwarded,
    long ok,
    long deadlineExceeded,
    long failed,
    List<AdView> recentAds,
    long sampledAtMs) {
  public StoreStats {
    Objects.requireNonNull(storeUrl, "storeUrl");
    recentAds = List.copyOf(recentAds);
  }

  public record AdView(String redirectUrl, String text) {
    public AdView {
      Objects.requireNonNull(redirectUrl, "redirectUrl");
      Objects.requireNonNull(text, "text");
    }
  }
}
