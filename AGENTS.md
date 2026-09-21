# AGENTS.md — SpanLease contribution instructions

SpanLease investigates pre-deadline localization of persistent circular waits across
blocking Java/gRPC calls and explicit bounded application worker slots, using
OpenTelemetry-compatible **custom telemetry**. It is a research prototype under
development; do not claim implemented results, novelty, soundness or timing guarantees
without their evidence.

## Read before work

1. `requirements.md` — current scope, A1–A6, predicate, verdicts and obligations.
2. `design.md` — runtime architecture, proposed v2 contracts, interval/cut algorithms,
   invariants I1–I5, race rows R1–R16 and verification.
3. `tasks.md` — assigned owner, prerequisite task IDs, handoffs and gates G0–G3.
4. `docs/decisions.md` — append-only history; check superseding entries.
5. `docs/literature-review.md` for research/claim changes. The technical review and
   amendment documents preserve rationale and review history.

The 2026-09-19 revised requirements/design are the current implementation baseline;
the revision-2 proposal PDF is historical motivation, not authority to restore a
superseded defect. The revised event/report contract is **v2 pending G0 all-developer
sign-off**. Do not assert that approval exists or silently mix old v1 replay fixtures
with new semantics. Later contract changes still require review and versioning.

## Ownership and execution order

- **Dev A:** build/CI, testbed/driver, independent truth, scenario/fault harness and runs.
- **Dev B:** event DTOs, application gate, RPC integration, scanner/checkpoints/export,
  instrumentation baselines and artifact packaging.
- **Dev C:** report/replay DTOs, analyzer, correctness/oracle work, analyzer baselines,
  statistics and manuscript coordination.

Every implementation task has one accountable owner and explicit prerequisites in
`tasks.md`. Work only on ready tasks; do not invent missing cross-lane interfaces.
Contract/model discussions and isolated feasibility spikes may precede G0; production
contract implementations may not. Handoff examples and tests are part of completion.
Do not spawn/delegate agents unless the user explicitly requests it for the task.

## Fixed stack and boundaries

Java 21; Gradle Kotlin DSL; grpc-java **blocking unary client stubs**; OTel SDK LogRecords
over OTLP; JUnit 5 + AssertJ + jqwik; Jackson; google-java-format. No Spring, Lombok,
direct Guava use or unreviewed dependencies. Dependency versions belong in
`gradle/libs.versions.toml`, never version literals in build files. Defaults are
design §4.1. Existing catalog entries are pins to validate, not proof of compatibility.

`common` contains transport-independent immutable DTOs/validation, with JDK/Jackson
only. SDK encoding belongs in instrumentation; OTLP decoding belongs in analyzer.
Analyzer must never depend on instrumentation/testbed or read independent truth.
Truth-to-observation mapping belongs exclusively in the evaluation harness.

## Semantics that must survive every change

- One modeled execution is one admitted handler job. A gRPC callback Runnable is not
  automatically an execution. Transport/callback executor and monitored application
  gate are separate. Ordinary ServerInterceptor is not a pre-dispatch arrival hook.
- Blocking events describe the helper's logical call interval, not physical JVM parks.
  One outstanding downstream invocation per execution; retries use new identities;
  transparent retries, hedging and other alternative waits are outside supported scope.
- I1: local state transition, sequence allocation and publication attempt are serialized.
  I2: acquire before run. I3: actual task exit/end before release.
  I4: no telemetry queue-space/network/I/O waits on application paths; short state-lock
  contention is allowed and measured. I5: metadata/causal references before send/close.
- Cancellation request, response send/receive, helper return, task exit and slot release
  are distinct. Cancellation alone never frees a still-running worker.
- Init observes free slots before admission. Checkpoints certify only received
  contiguous prefixes, including idle instances. Missing suffixes/gaps never disappear
  because Δ elapsed; permanent loss blocks certification until a clean run.
- Unknown state is neither free nor held. Expired leases do not remove ownership or
  prove progress. Both confirmed verdicts require the declared scope's complete evidence.
- Cross-host order comes from exact causal references, including response parents.
  Local sequence/monotonic time never orders different hosts. Physical time supports
  guaranteed interval overlap under qualified ε; it never invents causal edges.
- Persistence comes from complete uninterrupted histories of the same dependencies,
  not repeated evaluation ticks or unchanged node membership. Temporal pruning cannot
  produce a negative verdict.
- Report an evidence set/residual, not an irreducible/minimum root cause. A negative
  verdict applies to its reported scope/cut; a positive verdict is historical evidence,
  not authorization to cancel work.

## Contracts and decisions

Event/manifest/replay/report changes require a contract-tagged PR and all three
developers' approval. Wire-visible changes bump schema_version and migrate fixtures
explicitly. The v2 design in this revision is a **review candidate**, not an exception
to that rule. Do not repurpose attributes or invent undocumented control observations.

Append a decision in the same change whenever resolving ambiguity or changing a
contract/architecture. Never rewrite prior decision entries; supersede them.
If a task reveals a material unresolved semantic choice, supply a concrete proposed
change and counterexample rather than guessing an implementation.

## Tests and build

After A-BOOT supplies the build:

```bash
./gradlew build
./gradlew :analyzer:test
./gradlew integrationTest
```

Run checks relevant to the change; format Java with google-java-format. Do not claim
tests passed if CI skipped them or no wrapper exists. Each touched R1–R16 row requires
its targeted test in the same implementation change. Use latches/barriers/virtual time,
never sleeps-for-sync. Independent oracles must not reuse production reducer logic.

Two real integration litmus tests are mandatory after G2:
1. Deterministic closed wait → CONFIRMED_DEADLOCK with exact expected evidence set.
2. Fully observed spare-capacity cyclic pattern → CONFIRMED_NO_DEADLOCK.

Seed workload/fault RNGs and inject them through constructors. Production identity
uniqueness is separate from deterministic test IDs. Deterministic replay fixes the
manifest, received envelopes, query/evaluation times and configuration; event payloads
alone do not determine receipt-time gap ages or freshness.

## Code and research style

Package prefix `dev.spanlease.<module>`; one top-level class per file; immutable data
as records; explicit public signature types. Javadoc states invariants. Comments explain
non-obvious decisions/invariants rather than narrating code. Keep the implementation
small, at the responsible layer, without speculative services/frameworks.

No “standard telemetry” description of detector inputs. Executor depth is custom/JMX
data, not attributed to RPC semantic conventions. No “first” claim without the reviewed
literature comparison. No transfer of DDMon proofs to a Java/passive adaptation.
No promise of universal safe degradation outside observable assumptions.

Document behavior, verification, unresolved dependencies and material limitations in
handoffs. Publication/submission is not authorized by implementing the artifact.
**Do not edit `CLAUDE.md` as part of this documentation revision.**
