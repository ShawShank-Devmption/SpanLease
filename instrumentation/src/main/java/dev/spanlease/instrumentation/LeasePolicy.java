package dev.spanlease.instrumentation;

/**
 * Execution-lease generation policy (design §4.1). A block becomes lease-eligible once its age
 * reaches {@code ageThresholdMs}; leases renew every {@code renewalMs} and expire {@code renewalMs
 * * expiryFactor} after emission. The factor is at least 3 so a single delayed renewal does not
 * open a coverage hole; it is not a GC-pause tolerance guarantee.
 */
public record LeasePolicy(long ageThresholdMs, long renewalMs, int expiryFactor) {
  public static final LeasePolicy DEFAULT = new LeasePolicy(500, 500, 3);

  public LeasePolicy {
    if (ageThresholdMs <= 0 || renewalMs <= 0) {
      throw new IllegalArgumentException("lease age threshold and renewal must be positive");
    }
    if (expiryFactor < 3) {
      throw new IllegalArgumentException("lease expiry factor must be at least 3");
    }
  }

  public long expiryMs() {
    return renewalMs * expiryFactor;
  }
}
