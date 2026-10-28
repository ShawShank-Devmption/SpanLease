package dev.spanlease.testbed;

import hipstershop.Demo;
import java.util.ArrayDeque;
import java.util.List;

/** Thread-safe display counters; recent ads are bounded independently of run length. */
final class StoreTraffic {
  private long forwarded;
  private long ok;
  private long deadline;
  private long failed;
  private final ArrayDeque<StoreStats.AdView> recent = new ArrayDeque<>();

  synchronized void forwarded() {
    forwarded++;
  }

  synchronized void ok(Demo.AdResponse response) {
    ok++;
    for (Demo.Ad ad : response.getAdsList()) {
      if (recent.size() == 5) recent.removeFirst();
      recent.addLast(new StoreStats.AdView(ad.getRedirectUrl(), ad.getText()));
    }
  }

  synchronized void deadline() {
    deadline++;
  }

  synchronized void failed() {
    failed++;
  }

  synchronized StoreStats sample(String storeUrl, int edgePort, long nowMs) {
    return new StoreStats(
        storeUrl, edgePort, forwarded, ok, deadline, failed, List.copyOf(recent), nowMs);
  }
}
