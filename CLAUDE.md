# CLAUDE.md

SpanLease — OpenTelemetry-compatible runtime monitor that localizes distributed deadlocks
(circular waits across gRPC calls + bounded executor slots) in Java services before RPC
deadlines. Research project; semantics come from `SpanLease_Research_Proposal_r2.pdf`.

**Follow `AGENTS.md` in full — it is the canonical agent instruction file for this repo.**
Reading order: `requirements.md` → `design.md` → `tasks.md` → `docs/decisions.md` → the
proposal PDF. Check `docs/decisions.md` before "fixing" any apparent deviation from the
docs; append to it when you resolve an ambiguity. Dependency versions come only from
`gradle/libs.versions.toml`; parameter defaults from `design.md` §4.1.

## Quick reference

- Stack: Java 21, Gradle Kotlin DSL, grpc-java blocking stubs, OTel SDK (LogRecords over
  OTLP), JUnit 5 + AssertJ + jqwik, Jackson, google-java-format. No Spring/Lombok/Guava;
  no new deps without a `design.md` change.
- Build/test: `./gradlew build` · `./gradlew :analyzer:test` · `./gradlew integrationTest`.
- Packages: `dev.spanlease.<module>`. Records for immutable data. Javadoc states invariants.

## The five things most likely to be gotten wrong

1. `analyzer` must never depend on `instrumentation`/`testbed` — it sees observations
   only; ground truth never enters analyzer inputs.
2. Event wire schema (`design.md` §4) and verdict JSON (§8) are frozen — contract PR +
   all-dev sign-off + `schema_version` bump to change.
3. Concurrency invariants I1–I5 and race matrix R1–R16 (`design.md` §7) are normative;
   touching one requires its targeted test in the same PR. Analyzer core is single-writer.
4. Conservative direction: any missing/ambiguous evidence ⇒ inconclusive verdict naming
   the gap. An unobserved unit is neither free nor held. Both CONFIRMED verdicts require
   complete observed evidence.
5. `local_seq` orders events on ONE instance only; cross-host order comes solely from the
   causal (send→arrival) relation; `wall_time` only feeds persistence thresholds with ±ε
   narrowing.

Litmus tests that must always pass: deterministic trigger ⇒ `CONFIRMED_DEADLOCK` with
exact core; cyclic pattern with spare capacity ⇒ `CONFIRMED_NO_DEADLOCK`.

If a task conflicts with `design.md` or needs a contract change, stop and raise it —
don't improvise around the contract.
