# SpanLease project understanding and review guide

This document explains the current SpanLease proposal, its intended implementation, possible novelty, and its limitations. It reflects the revised requirements and design documents. It is a specification review, not evidence that the system has been implemented or proved.

## Current status

The repository contains requirements, design, task planning, research-review material, a proto, and replay scaffolding. It does not yet contain the complete Java implementation, Gradle wrapper, executable analyzer, integration testbed, correctness proof, or empirical results.

The v2 observation and verdict contract is a review candidate. It still requires the G0 contract review and sign-off by all three developers. The proposal PDF is historical motivation; the revised requirements and design resolve defects found during review.

## Problem

SpanLease targets persistent circular waits in synchronous Java/gRPC services with explicitly bounded application worker executors.

Example:

1. A's handler owns A's only slot and calls B.
2. B's handler owns B's only slot and calls C.
3. C's handler owns C's only slot and calls A.
4. A's new request queues because A's only slot is held by the first handler.

The first handler cannot finish until B responds, B cannot finish until C responds, and C cannot finish until A admits the queued request. A deadline can eventually break the condition, so the exact target is a persistent circular wait before external timeout or cancellation.

SpanLease must distinguish:

- Slow progress: a dependency is delayed but will eventually complete.
- Saturation: all slots are occupied, but owners are progressing.
- Closed circular waiting: every member is blocked and every capacity unit needed for progress is held inside the blocked set.

A service-level cycle alone is insufficient. If A has another available slot, a final request can execute there and the chain can unwind.

## Supported deployment

The first implementation supports Java 21, blocking unary grpc-java client stubs, one outstanding downstream call per handler, and fixed-capacity application worker gates.

The application gate is separate from gRPC's transport/callback executor. The intended path is:

gRPC receipt -> early arrival observation -> decode -> application-gate submission -> slot assignment or queueing -> handler job -> downstream blocking RPCs -> actual job exit -> slot release.

This is an explicit deployment restriction. The system does not claim transparent correctness for every Java/gRPC executor configuration. Async, streaming, reactive, actor, virtual-thread, hedged, multi-outstanding-wait, dynamic-capacity, and hidden-resource cases are outside the initial guarantee.

## Entities

| Entity | Role |
|---|---|
| Instance epoch | One process lifetime with its own local event sequence |
| Invocation | One downstream call attempt, identified at both endpoints |
| Execution | One admitted application handler job that owns a slot |
| Queue entry | A ready job waiting for admission; it has no execution ID yet |
| Resource | One bounded application pool |
| Resource unit | One named worker slot |
| Lease | Fresh evidence that a particular blocking interval was observed |
| Checkpoint | A boundary proving that a complete local event prefix was received |
| Evidence set | The surviving closed blocked members and their witnessing edges; not claimed to be minimal |

A queued request is not an execution, and a span is not ground truth about resource ownership.

## Architecture

The intended modules are:

| Module | Responsibility |
|---|---|
| common | Immutable event, manifest, ID, validation, and report types |
| instrumentation | Application gate, blocking helper, RPC hooks, leases, checkpoints, LogRecord encoding and export |
| testbed | Services, driver, deterministic scenarios and independent truth |
| analyzer | OTLP receiver, ordered histories, certification, cuts, reduction, persistence and reports |
| baselines | Evaluation-only alternative monitors and adapters |
| eval | Runs, fault schedules, truth comparison, statistics and reproduction |

The analyzer never imports the testbed or instrumentation implementation and never receives ground truth. Receiver threads validate and enqueue; a single analyzer core thread owns mutable analysis state.

## Proposed v2 telemetry

The proposed v2 contract has thirteen record types:

| Record | Meaning |
|---|---|
| resource.init | Initial free state of all declared slots |
| instance.checkpoint | Complete local-prefix boundary, including idle periods |
| rpc.invocation.sent | Client dispatch |
| rpc.invocation.arrived | Server transport receipt |
| resource.wait.begin | Ready job queued for capacity |
| resource.acquire | Slot assignment and execution creation |
| rpc.block.begin | Logical entry to a blocking helper |
| execution.lease | Fresh observation of the current block |
| rpc.response.sent | Server response-close boundary |
| rpc.block.end | Blocking helper return or failure |
| execution.cancel | Cancellation or rejection |
| execution.end | Actual application-job termination |
| resource.release | Slot release after termination |

Every event contains schema version, event type, instance epoch, local sequence, causal parent where applicable, wall time, monotonic time, and optional trace correlation.

Local sequence numbers establish order only within one instance. Cross-host order comes from exact send/arrival and response references. Wall time is used for persistence intervals, not causal ordering.

Initialization records prevent an unseen slot from being treated as free. Checkpoints expose lost suffixes that sequence numbers alone cannot reveal. A checkpoint certifies only a contiguous prefix from sequence 1; it does not repair a gap. A permanent gap blocks later certification for that instance in the first implementation.

## State and cancellation

Invocation state is ARRIVED -> READY -> QUEUED or ASSIGNED -> RUNNING -> ENDED.

Execution state is ASSIGNED -> RUNNING <-> BLOCKED -> ENDED.

Unit state is observed FREE -> HELD(execution) -> FREE; unknown state remains unknown.

Cancellation is not equivalent to termination. A cancelled running job may continue holding its slot. execution.end records actual handler exit, and only then may resource.release occur. A queued cancel can win against assignment; if assignment wins first, acquire followed by cancel is legal.

## Analyzer reasoning

The analyzer:

1. Validates and deduplicates observations.
2. Buffers events by local sequence.
3. Certifies only complete checkpointed prefixes.
4. Retracts causal cuts that contain children without parents.
5. Reconstructs executions, queue entries, units, waits, and owners.
6. Treats missing or expired evidence as unknown/inconclusive.
7. Reduces members with observed progress witnesses.
8. Checks persistence over uninterrupted histories.
9. Emits a deterministic verdict and evidence report.

The graph contains execution nodes, queue-entry nodes, pool nodes, and slot nodes. Important edges are execution-to-execution waits, execution-to-queue waits, queue-to-pool capacity waits, and slot-to-execution ownership.

For a closed set S:

- Every execution in S is observed blocked.
- Every queue entry in S is observed waiting.
- Every blocked execution's counterpart belongs to S.
- Every eligible slot needed by a queued member is owned by an execution in S.
- These conditions persist for at least tau.

Unknown information never counts as progress:

Missing counterpart is not a progressing counterpart.

Expired lease is not a released slot.

Unseen acquire is not a free slot.

The structural reduction removes a queue entry if an eligible slot is observed free or owned by a progressing/discharged member. It removes an execution if its observed counterpart is progressing or discharged. If the complete structural candidate becomes empty, the queried cut can receive CONFIRMED_NO_DEADLOCK.

If a structural candidate survives, the analyzer searches for a closed subset with uninterrupted, temporally qualified evidence for at least tau. The temporal pass can support CONFIRMED_DEADLOCK or remain inconclusive; it must not convert insufficient persistence into a negative verdict.

The reported core is a residual evidence set. It may include multiple cycles and upstream dependents. It is not promised to be minimum-cardinality or irreducible.

## Persistence and leases

For each wait, ownership, and queue assertion, the analyzer tracks the beginning event, exact identity, first later closing/change event, complete sequence history between endpoints, and a conservative interval after clock uncertainty.

For an observed interval [a,b] with timestamp uncertainty epsilon, the guaranteed interval is [a+epsilon,b-epsilon]. Across several required assertions, the analyzer intersects all conservative intervals and requires overlap of at least tau.

A new invocation, owner, queue transition, cancellation, or block transition closes the previous assertion even if the same execution IDs remain.

A lease carries the exact current block reference. It proves that the block was observed at lease emission; it does not reserve the execution or slot until expiry. Expiry prevents stale evidence from remaining eligible, but does not make ownership disappear or create a free slot.

The research must demonstrate what leases add beyond complete transition histories and checkpoints. Their possible value is freshness control and a tunable overhead/latency tradeoff, but this is not established yet.

## Verdicts

| Verdict | Meaning |
|---|---|
| CONFIRMED_DEADLOCK | Complete causally admissible evidence supports a closed wait over the reported interval |
| CONFIRMED_NO_DEADLOCK | Complete evidence permits observed-progress reduction to empty at the reported cut and scope |
| CANDIDATE_INCONCLUSIVE | Evidence is missing, stale, ambiguous, too short, or temporally unqualified |
| INSUFFICIENT_OBSERVABILITY | Known unsupported deployment or invalid instrumentation/identity makes evaluation impossible |

A positive result is historical evidence about a certified interval. It is not proof that the condition still exists and is not permission to cancel work.

## Invariants

- I1: local transition, sequence allocation, and publication attempt are serialized.
- I2: acquire precedes application execution and execution-scoped events.
- I3: actual task exit/end precedes release.
- I4: application paths never wait for telemetry I/O, network, or queue capacity.
- I5: causal metadata is prepared before transport send or close.

Short state-lock contention is allowed and must be measured. Telemetry failure must not alter application semantics.

## Testbed and ground truth

The testbed uses a generic unary Chain.Call RPC whose request describes remaining hops. The same implementation can be deployed as two, three, or five instances with configurable work and hold times.

The deterministic trigger coordinates the workload so the closed wait forms. Trigger barriers must be released before measuring the wait; otherwise the barrier itself could become an unmodeled dependency.

The independent truth recorder observes gate transitions, call boundaries, worker exit, and slot state using independent IDs and counters. It does not consume SpanLease events or use the production reducer. Only the evaluation harness maps truth identities to reported identities.

## Correctness obligations

The project has not yet discharged:

- S1: positive verdicts witness the predicate over the reported interval.
- S2: negative verdicts refute the modeled predicate at their declared scope and cut.
- L1: qualifying waits are eventually detected under delivery, clock, and scheduling assumptions.
- Reduction termination and order independence.
- Preservation of closure after temporal pruning.

Finite tests and zero observed failures do not prove these statements. Proofs and real grpc-java integration are both required.

## Novelty assessment

The strongest direct competitor identified is DDMon (OOPSLA 2025), which already provides distributed RPC deadlock monitoring, proxy monitors, formal correctness, mechanization, and an Erlang/OTP implementation. DDMon also discusses replicated workers.

SpanLease therefore cannot claim novelty merely for RPC deadlock detection, wait-for graphs, multiple workers, leases, or inconclusive verdicts.

The defensible potential contribution is narrower:

> A practical evidence protocol for bounded Java/gRPC executor admission, using delayed one-way telemetry to justify historical closed-wait verdicts and explain when incomplete evidence prevents them.

That claim requires a formal model, implementation, and fair comparisons. Relevant comparisons include Hindsight, CRISP, live/in-progress spans, invocation-level monitors, and enriched live spans using the same queue and slot facts.

## Evaluation

Planned baselines are metrics-only saturation, completed spans plus timeout diagnosis, ordinary live spans through the same analyzer, enriched live spans with identical queue/slot facts through the same analyzer, invocation-level monitoring, a documented DDMon-inspired adaptation, and Hindsight-style triggered buffering where feasible.

Workloads must cover true closed waits, spare capacity, ordinary saturation, slow progress, multiple cycles, upstream dependents, response-before-release, cancellation, idle instances, lost final records, crashes, capacities 1/2/4, replicas, and deadlines.

Measure detection latency, useful deadline time, execution/slot localization, false-confirmation rates, inconclusive causes, CPU, memory, throughput, tail latency, and telemetry volume. Evaluation ticks from one incident are not independent trials. Zero observed errors is not a zero-error proof.

## Caveats

| Caveat | Consequence |
|---|---|
| Explicit application gate required | Limited deployment generality |
| Blocking unary and one outstanding wait only | No claim for async, streaming, actors, hedging, or virtual threads |
| Static manifest and capacity | Autoscaling and dynamic membership unsupported |
| Whole-scope completeness | One unresolved instance can prevent confirmation |
| Permanent gaps block certification | Small telemetry loss can cause long inconclusive periods |
| Clock qualification required | Undetected skew/drift invalidates temporal guarantees |
| Hidden dependencies excluded | Undeclared locks, databases, brokers, or logical waits are outside the model |
| Central analyzer and shared state lock | Throughput and observer-effect limits need measurement |
| Full-history retention | Bounded-memory long-running operation is not yet designed |
| Residual evidence set | Output may include multiple cycles and dependent waiters |
| Lease value unresolved | Must be shown beyond checkpoint/history evidence |
| Runtime/model alignment unproved | Integration tests and formal review remain essential |
| No remediation policy | A verdict does not authorize cancellation |

The most defensible current description is: SpanLease proposes a monitor for persistent RPC/executor circular waits in explicitly bounded Java/gRPC deployments. It combines invocation, queue, slot, and completeness observations to reconstruct causally consistent evidence and distinguish confirmed conditions from missing information. Implementation, proofs, and comparative results remain to be established.
