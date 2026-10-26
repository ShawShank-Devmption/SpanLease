# Extended implementation verification — 2026-10-27

This is local engineering evidence under the D13 solo implementation model. It does
not assert independent proof review, remote CI execution, G1–G3 acceptance or full
publication readiness.

Java 21 commands completed successfully:

```sh
./gradlew --no-daemon spotlessApply build :testbed:integrationTest
./gradlew --no-daemon spotlessApply build :testbed:integrationTest \
  --tests dev.spanlease.testbed.WorkloadMatrixTest
python3 -m unittest discover -s eval/tests -v
```

The targeted matrix rerun verifies that each reported evidence subset is itself
closed in physical truth throughout the claimed interval, rather than merely being
contained in the oracle's maximal closed set. Source changes to final-flush accounting
were subsequently compiled and unit-checked with `./gradlew --no-daemon build`.

Evidence includes:

- One shared bare/instrumented gate, independent job/slot/ordinal truth, explicit
  overflow, terminal snapshots and evaluator-only mappings.
- Ten live workload cases, including the coordinated 3-service/k=2 ring. All setup
  waiters leave before measurement. Negative cases and admission failure are separate.
- Loss, missing checkpoint, delayed/duplicated delivery and skew through real
  SDK/OTLP, with exact CLI replay of every emitted report.
- A service JVM killed while owning a blocked worker. Its final truth snapshot is
  explicitly truncated; stale checkpoints produce inconclusive reports and no release
  is fabricated.
- Real deadline-before-admission, response/deadline and cancel/exit/release orderings;
  identity propagation and duplicate admission tests.
- Actual SDK retry through the real receiver's bounded admission, partial success,
  nonretryable malformed status, message-size limits and concurrent batch admission.
- Fixed-context replay permutations, gaps, unknown slots, lost end/release, idle/crash
  horizons, request/response parents, clock qualification, lease/history limits and
  epoch reuse. Exact v2 golden sessions are in `fixtures/replays`.
- 409 complete small capacity snapshots compared with independent subset enumeration;
  native enriched SDK span facts reproduce strict analyzer reports, and buffer eviction
  cannot invent startup evidence.
- Five standard-library statistics checks. Run-level bootstrap intervals, zero-failure
  bounds and unscorable comparisons are distinct from repeated evaluation ticks.

The finite pilot/matrix runner and protocol are in `eval/`. Baseline replay is an
information/retention comparison; live baseline cost isolation, active DDMon adaptation
and mechanism ablations remain separate. The correctness argument is reviewable but
not independently accepted. Replica/multi-host studies, clock/scheduling qualification,
large independently reproduced evaluation and the final manuscript remain tracked in
tasks.md. Passing these tests is not a soundness or novelty proof.
