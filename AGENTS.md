# AGENTS.md — instructions for coding agents (Codex, Claude, etc.)

SpanLease: an OpenTelemetry-compatible runtime monitor that detects and localizes
distributed deadlocks (persistent circular waits across RPCs + bounded executor slots)
in Java/gRPC services, **before** RPC deadlines fire.

## Read first, in this order
1. `requirements.md` — domain, scope, assumptions A1–A6, event contract, verdicts.
2. `design.md` — architecture, module layout, algorithms, invariants I1–I5, race matrix
   R1–R16, wire contract (§4), verdict JSON (§8), testing (§10).
3. `tasks.md` — your task, your lane (Dev A/B/C), working agreements, and the canonical
   LLM system prompt (follow it even if it wasn't pasted into your session).
4. `docs/decisions.md` — append-only log of resolved ambiguities. Check it before
   "fixing" code that seems to deviate from the docs; append an entry (same PR) whenever
   you resolve an ambiguity or change a contract.
5. `SpanLease_Research_Proposal_r2.pdf` — ultimate authority on semantics and claims.

## Stack (fixed)
Java 21 · Gradle (Kotlin DSL) · grpc-java **blocking stubs only** · OpenTelemetry SDK
(events = LogRecords over OTLP) · JUnit 5 + AssertJ + jqwik · Jackson · google-java-format.
Versions come only from `gradle/libs.versions.toml` — never write a version literal in a
build file. No Spring, no Lombok, no Guava, no new dependencies without a `design.md`
change PR. Parameter defaults (τ, Δ, ε, lease intervals, …) are fixed in `design.md` §4.1.

## Build & test
```bash
./gradlew build            # compile + format check + unit/property tests
./gradlew :analyzer:test   # per-module
./gradlew integrationTest  # end-to-end litmus tests (phase 3+)
```
Code must be formatted with google-java-format; CI rejects unformatted code.

## Non-negotiable rules
- **Module boundaries:** `common` has no grpc/testbed deps. `analyzer` NEVER depends on
  `instrumentation` or `testbed` — it consumes observations only. Ground truth never
  flows through analyzer inputs.
- **Frozen contracts:** event wire schema (`design.md` §4) and verdict JSON (§8). Changing
  them requires a `contract`-tagged PR approved by all three devs + `schema_version` bump.
- **Concurrency invariants (design.md §7):** state transition + `local_seq` assignment in
  one critical section (I1); acquire before run (I2); release after terminal (I3);
  instrumentation never blocks app threads (I4); metadata before send (I5). Analyzer core
  is single-writer. Every race row R1–R16 you touch needs its targeted test in the same PR.
- **Conservative-direction rule:** any ambiguity (missing event, expired lease, frontier
  gap, unobserved unit, persistence < τ) ⇒ inconclusive verdict naming the missing item.
  Never treat an unobserved unit as free or held. Both CONFIRMED verdicts need complete
  observed evidence.
- **Time discipline:** `local_seq` = local order only; cross-host order only via the
  causal relation; `wall_time` only for persistence thresholds with ±ε narrowing.
- **Determinism:** seeded RNG injected via constructor; no sleeps-for-sync in tests;
  analyzer output is a pure function of the observation set.
- **Litmus tests never break:** deterministic trigger ⇒ `CONFIRMED_DEADLOCK` with exact
  core; spare-capacity cycle ⇒ `CONFIRMED_NO_DEADLOCK`.
- **Terminology:** "OpenTelemetry-compatible **custom** telemetry", never "standard
  telemetry". Never attribute executor-depth metrics to OTel RPC semantic conventions.
  No soundness claims — S1/L1 are obligations, "designed to satisfy under A1–A6".

## Style
- Package prefix `dev.spanlease.<module>`; one top-level class per file; records for
  immutable data; explicit types in public signatures; Javadoc states invariants.
- Comments only for non-obvious invariants, citing design sections ("// I1: seq under
  slot lock") — never narration.
- Tests ship in the same change as the code, at the smallest layer that can express the
  behavior.

## When unsure
If a task is ambiguous, conflicts with `design.md`, or needs a contract change: stop and
raise the question or propose the design diff. Do not improvise around the contract.
