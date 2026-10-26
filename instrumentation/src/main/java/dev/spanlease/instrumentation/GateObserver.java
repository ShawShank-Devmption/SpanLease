package dev.spanlease.instrumentation;

/**
 * Optional application lifecycle observer shared by bare and instrumented gates. Receives only
 * application-owned job tokens and physical slot indices, never detector records or identities.
 * Implementations must buffer immediately without I/O and must not throw or call back into gates.
 */
@FunctionalInterface
public interface GateObserver {
  GateObserver NONE = (transition, job, slot) -> {};

  void observe(Transition transition, Object job, int slot);

  enum Transition {
    INIT,
    READY,
    QUEUE,
    ASSIGN,
    RUN,
    CANCEL,
    REJECT,
    RESPONSE,
    EXIT,
    RELEASE,
    FINAL
  }
}
