package dev.spanlease.common;

/** Schema-v2 verdict labels. */
public enum Verdict {
  CONFIRMED_DEADLOCK,
  CONFIRMED_NO_DEADLOCK,
  CANDIDATE_INCONCLUSIVE,
  INSUFFICIENT_OBSERVABILITY
}
