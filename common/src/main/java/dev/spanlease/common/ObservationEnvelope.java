package dev.spanlease.common;

import java.util.Objects;

/** One received observation plus analyzer-local receipt timing. */
public record ObservationEnvelope(
    ObservationEvent event, long receiptWallMs, long receiptMonotonicNs) {
  public ObservationEnvelope {
    Objects.requireNonNull(event, "event");
    ContractValidation.nonnegative(receiptWallMs, "receiptWallMs");
    ContractValidation.nonnegative(receiptMonotonicNs, "receiptMonotonicNs");
  }
}
