# SpanLease — Requirements & Project Context

> **Purpose of this file:** shared context for every developer and every LLM working on this
> project. Read this before `design.md` and `tasks.md`. It states *what* we are building and
> *why*; `design.md` states *how*; `tasks.md` states *who does what, in what order*.

---

## 1. One-paragraph summary

**SpanLease** is an OpenTelemetry-compatible runtime monitor for synchronous Java/gRPC
services that use explicitly configured **bounded executors**. It emits ten custom event
types (invocation dispatch/arrival, queue entry, executor-slot acquire/release,
blocking-wait boundaries, renewable liveness leases, cancellation, termination),
reconstructs a **causally consistent, capacity-aware wait-for graph**, and **localizes
persistent circular waits (distributed deadlocks) before RPC deadlines fire**. When the
observations do not justify confirmation, it returns an explicit inconclusive verdict
instead of guessing.

This is a research systems project (target venues: ACM Middleware, ICPE, IEEE IC2E). The
source of truth for all claims and semantics is `SpanLease_Research_Proposal_r2.pdf` in
this repo. Nothing below overrides that document; this file condenses it.

## 2. The problem

Microservice systems built on synchronous RPC can enter **persistent circular waits** that
involve both in-flight RPC executions and bounded executor capacity:

- Service A's handler (holding a slot in A's executor) makes a blocking call to B.
- B's handlers (holding all of B's slots) block on calls to C.
- C's handler blocks on a call back to A — but A's slot is held by the first handler.
- The cycle is permanent: no member can progress without external intervention.

Two evidence classes exist while this persists, and **neither is sufficient**:

| Evidence | Timing | Limitation |
|---|---|---|
| Completed spans (conventional tracing) | Arrive **on span end** — i.e., only after the stalled calls hit their deadlines | Full request identity, but outside the intervention window |
| Aggregate executor/in-flight metrics | Continuous | Counts and occupancy only — cannot name *which* execution holds *which* slot, or which invocation waits on which execution |

SpanLease fills the gap: **live, request- and slot-level evidence, before the deadline**.

### Three conditions that must never be conflated

1. **Slow request** — making progress, just late. Resolves on its own. Cancelling destroys work.
2. **Executor saturation** — all slots busy, arrivals queue. Evidence of *pressure*, not deadlock. May resolve when any owner completes.
3. **Persistent circular wait** — every member blocked, every satisfying capacity unit held *inside* the set. Does not resolve without intervention. **This is the only target condition.**

Saturation and circular waiting are neither necessary nor sufficient for each other. A
detector keyed on saturation produces false positives (saturated but progressing) and false
negatives (cycle among partially-occupied executors). This distinction drives the entire
design: the predicate is over **individual capacity units (slots)**, not services.

## 3. Scope — the setting we claim

- **Synchronous blocking unary gRPC**, Java client and server, generated blocking stubs.
- Servers with an **explicitly bounded executor** whose capacity and slot identities are
  known to the instrumentation.
- **AND-wait semantics**: a blocked execution needs exactly its one outstanding response;
  nothing else unblocks it.
- Trace/invocation context propagated correctly on every hop.

**External validity, stated honestly:** default gRPC Java deployments do *not* necessarily
match this (executors may be unbounded/cached). Our claims apply to deployments configured
as above, and the evaluation must report that as a generalisability restriction.

## 4. Assumptions (A1–A6) — memorize these

| ID | Assumption | If violated |
|----|-----------|-------------|
| A1 | Instrumented resources only: every capacity unit in a confirmable cycle emits acquire/release events | Cycle through an uninstrumented lock/pool/broker cannot be confirmed → analyzer **must degrade to inconclusive**, never assume absence |
| A2 | Context propagation correct; invocation identity stable end-to-end | Wait edges cannot be attributed; verdicts unsound. Partially detectable via coverage accounting |
| A3 | Priority-channel events may be delayed, reordered, duplicated — but delivery delay for confirmation-relevant events is bounded by **Δ** | Confirmation withdrawn; only inconclusive verdicts permitted |
| A4 | Physical clock skew between hosts bounded by **ε**; physical time used *only* for persistence thresholds, never for cross-host ordering | Persistence intervals widen by 2ε; ordering unaffected (it rests on causal relations) |
| A5 | Executor capacity and slot identity observable and stable within an evaluation window | Capacity-aware predicate unevaluable → inconclusive |
| A6 | Instrumentation does not itself drop terminal events under load | Held-forever ownership would be inferred from missing releases. Mitigated (not eliminated) by lease expiry |

## 5. Non-goals

- No claims for reactive, async, streaming, actor-based, or virtual-thread systems.
- No soundness claims under arbitrary event loss, unbounded delay, or unsynchronized clocks.
- No detection of livelock, starvation-without-circularity, or application-level logical
  dependencies not represented by an observed wait.
- **No remediation policy.** SpanLease produces evidence; acting on it (cancelling work) is
  a separate problem with separate correctness requirements. A confirmed verdict is *not* a
  licence to cancel.
- No claim of operating on *standard* OpenTelemetry telemetry alone. SpanLease rides
  standard OTel APIs/transport (W3C context propagation, logs data model, OTLP) but defines
  **custom event schemas** — always described as "OpenTelemetry-compatible custom telemetry".

## 6. Core entities (domain vocabulary — use these names in code)

| Entity | Identity | Meaning |
|---|---|---|
| Service instance | `instance_id` | A single addressable process with its own local event sequence |
| RPC invocation | `invocation_id` | A logical downstream call; generated at the client, propagated in request metadata; **same id at both endpoints** |
| Execution | `execution_id` | Server-side resource-holding entity, created when an invocation is assigned a capacity unit. **Not the same as a span** |
| Queue entry | — | An arrived invocation not yet assigned a unit. Has arrival + wait events but no `execution_id` and **no application span** (invisible to span-based instrumentation — a key reason the event contract starts before span creation) |
| Resource type | `resource_id` | A bounded executor/pool with declared capacity k |
| Resource instance | `resource_instance_id` | One capacity unit, e.g. `svcB:exec-main:slot-2`. Modelling *units* (not types) makes the predicate correct for k > 1 |
| Wait edge | — | Observed blocking dependency: execution→invocation, or queue-entry→resource-type |
| Ownership edge | — | Resource instance held by an execution (acquire with no causally later release) |
| Lease | — | Liveness assertion bounding the *staleness* of an ownership/wait claim. Does not by itself prevent phantom cycles — only causal-consistency does |
| Observation | — | An event record that may be delayed, reordered, duplicated, or lost. **The analyzer's input is observations, never system state** |
| Candidate global state | — | A set of per-instance local states forming a **consistent cut** under the causal precedence relation |

## 7. The event contract (10 event types)

Emitted on a dedicated **priority channel** exempt from trace sampling. Ordinary trace
sampling is *not* the detector's input model; sampled completed spans are supplementary
operator context only, never verdict input.

| Event | Emitted by / when | Key fields |
|---|---|---|
| `rpc.invocation.sent` | Client interceptor, at dispatch of a blocking unary call | invocation_id, caller execution_id, target service |
| `rpc.invocation.arrived` | **Server transport interceptor, on receipt — before any executor assignment or application span** | invocation_id, arrival local_seq |
| `resource.wait.begin` | Server, when an arrived invocation queues because no unit is free | invocation_id, resource_id, requested capacity semantics |
| `resource.acquire` | Server, when a unit is assigned. **Creates the execution** | execution_id, resource_instance_id, invocation_id |
| `rpc.block.begin` | Client interceptor within a server execution, when the calling thread parks | execution_id, invocation_id (the awaited call) |
| `execution.lease` | Background scanner: after an age threshold, then at a renewal interval, while an execution stays blocked | execution_id, original start reference, observed age, expiry |
| `rpc.block.end` | Client interceptor, on response, error, or deadline | execution_id, invocation_id, outcome |
| `resource.release` | Server, when a unit returns to the pool | execution_id, resource_instance_id |
| `execution.end` | Server, on normal termination | execution_id, outcome |
| `execution.cancel` | Server or client, on cancellation or deadline propagation | execution_id, invocation_id, cancellation source |

Common schema fields on **every** event: `schema_version`, `event_type`, `instance_id`,
`local_seq` (monotonic per instance — **local order only**, never a cross-host ordering
device), `causal_parent`, `trace_id`/`span_id` (correlation for operators), `wall_time`
(persistence thresholds only), plus the identity fields above; `lease_expiry` on lease
events. Duplicates are idempotent by `(instance_id, local_seq)`.

**Design correction to respect:** the lease record produced after an age threshold is *not*
a start event. Arrival and acquire events are emitted immediately (they are cheap);
`execution.lease` explicitly carries the original start reference and observed age so the
analyzer never infers a start time from a delayed record's arrival time.

## 8. Verdicts (four-valued lattice)

Verdicts differ by **what the evidence establishes**, not by degree of confidence:

| Verdict | Meaning |
|---|---|
| `CONFIRMED_DEADLOCK` | A confirmed terminal set exists at a candidate global state; all predicate conditions C1–C6 hold; coverage over participating resource types is complete under A1–A6 |
| `CONFIRMED_NO_DEADLOCK` | For the queried cut/window, iterative reduction empties the candidate set — the predicate is **refuted**, not merely unestablished |
| `CANDIDATE_INCONCLUSIVE` | A non-empty blocked set survives reduction, but a required observation is missing/late/attributable to an unobserved unit, or persistence hasn't reached τ. Actionable: the report names the missing item |
| `INSUFFICIENT_OBSERVABILITY` | Predicate not evaluable at all (uninstrumented participating resource, broken propagation). A configuration defect, not a transient gap |

Every verdict ships with a **coverage report** (which resource types were instrumented,
which units had observed ownership, which hops carried propagated identity, which
observations were outstanding). Coverage is a first-class output.

**Detection confidence ≠ remediation safety.** A confirmed verdict establishes the
predicate held at some consistent cut in the persistence interval — not that it still
holds now, nor that cancelling any member is safe or correct.

## 9. Correctness obligations (to be proved, not assumed)

- **S1 (safety):** `CONFIRMED_DEADLOCK` over a reported core and persistence interval ⇒
  under A1–A6 there exists a causally consistent cut within that interval at which the
  capacity-aware predicate holds over the reported core.
- **L1 (conditional liveness):** a qualifying deadlock persisting longer than τ + Δ + 2ε,
  with all required observations delivered within Δ ⇒ SpanLease eventually reports
  `CONFIRMED_DEADLOCK` over a core contained in the true deadlocked set.

No soundness claim appears anywhere before proofs exist. Interim framing: "designed to
satisfy S1 and L1 under the stated assumptions."

## 10. Deliverables (project outputs)

1. **Bounded-executor gRPC testbed** — three Java services, fixed-capacity executors with
   visible slot identity, deterministic deadlock trigger, and an **independent ground-truth
   recorder** producing request- and slot-level truth.
2. **Instrumentation library** — interceptors + slot-tracking executor + lease scanner +
   priority OTLP channel, emitting the 10-event contract.
3. **Analyzer** — consistent-cut construction, capacity-aware graph, iterative reduction,
   coverage accounting, four-valued verdicts, evidence-core reports.
4. **Baselines & ablations** — metrics-only saturation detector; completed-trace/post-timeout
   diagnosis; in-progress-spans feeding the *same* analyzer (the strongest baseline);
   Cheriton–Skeen-style RPC wait-for monitor; DDMon-inspired observer; triggered-tracing
   buffering; plus capacity-unaware, lease-free, and causality-free ablations.
5. **Evaluation & reproducibility package** — workload/fault matrix runs, metrics with
   confidence intervals, seeds, scripts; every reported figure reproducible on a fresh
   environment.

## 11. Success criteria (what "working" means)

- The testbed deadlocks **on demand** and ground truth identifies the exact executions and
  units involved, independently of any SpanLease event (P0 exit criterion).
- A queued invocation is observable while it has no execution and no application span
  (P1 exit criterion — the primary integration risk).
- Reconstructed slot occupancy matches the executor's own accounting for the whole run (P2).
- Client wait edges join server counterparts on `invocation_id` with no unmatched edges
  under nominal conditions (P3).
- Analyzer reproduces ground truth on the testbed and returns `CONFIRMED_NO_DEADLOCK` for
  cyclic call patterns with spare capacity (P5 — the capacity-awareness litmus test).
- Under fault injection (loss, delay, reordering, duplication, skew, crashes, partitions)
  the system degrades to inconclusive verdicts — **it never confirms what evidence doesn't
  support**.

## 12. Glossary of parameters

| Symbol | Meaning |
|---|---|
| τ (tau) | Persistence threshold — condition must hold this long before confirmation |
| Δ (Delta) | Assumed bound on delivery delay for confirmation-relevant events |
| ε (epsilon) | Assumed bound on physical clock skew between instrumented hosts |
| k | Declared capacity of a bounded executor (number of slots) |

## 13. Reading order for a new contributor (human or LLM)

1. This file.
2. `design.md` — architecture, module boundaries, algorithms, concurrency rules, testing.
3. `tasks.md` — your phase, your tasks, the shared LLM system prompt.
4. `SpanLease_Research_Proposal_r2.pdf` — authoritative source for any ambiguity.
