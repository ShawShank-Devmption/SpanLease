package dev.spanlease.instrumentation;

import dev.spanlease.common.ObservationEvent;

/** Non-blocking publication boundary used on application paths. */
@FunctionalInterface
public interface EventSink {
  /** Attempts immediate publication and returns false when the bounded pipeline is full. */
  boolean tryPublish(ObservationEvent event);
}
