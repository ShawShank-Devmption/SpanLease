# SpanLease — Technical Design

> **Purpose:** the complete technical specification an LLM or developer needs to build
> SpanLease. Requirements and domain context live in `requirements.md`; work breakdown in
> `tasks.md`. This document is authoritative for interfaces, algorithms, invariants,
> concurrency rules, and testing. Where this file and the proposal PDF disagree, the PDF
> wins — flag the conflict, don't silently pick one.

---

## 1. Goals, deliverables, outputs

### Goals
- G1: Emit the 10-event contract from real Java/gRPC services with bounded executors, with
  correct emission-order invariants, at bounded overhead.
- G2: Reconstruct causally consistent candidate global states from delayed / reordered /
  duplicated / lossy observations.
- G3: Evaluate the capacity-aware deadlock predicate (C1–C6) and emit four-valued verdicts
  with evidence cores and coverage reports, **conservatively** — never confirm on
  incomplete evidence.
- G4: Beat/characterize against 6 baselines + 3 ablations on identical workloads with
  identical ground truth.

### Concrete outputs
| Output | Form |
|---|---|
| Testbed | 3 gRPC Java services + workload driver + deterministic deadlock trigger + ground-truth recorder |
| Instrumentation library | Reusable Java library (interceptors, `SlotTrackingExecutor`, lease scanner, priority exporter) |
| Analyzer | Standalone Java service: OTLP logs receiver → verdict stream + JSON evidence reports |
| Verdict report | JSON: verdict, evidence core (nodes+edges), persistence interval, coverage report, missing observations |
| Evaluation package | Workload/fault configs, run scripts, raw results, analysis notebooks/scripts, seeds |

---

## 2. Technology decisions (fixed — do not relitigate in PRs)

| Decision | Choice | Rationale |
|---|---|---|
| Language | Java 21 (LTS) for testbed, instrumentation, analyzer | Proposal targets Java blocking stubs; one language across modules eases integration |
| RPC | grpc-java, protobuf, **blocking stubs only** | Scope requirement |
| Build | Gradle (Kotlin DSL), multi-module, protobuf-gradle-plugin | Standard for grpc-java |
| Telemetry transport | OpenTelemetry SDK; events as **OTel LogRecords** with attributes, exported over **OTLP/gRPC** on a dedicated pipeline (the "priority channel") | Reuses logs data model + OTLP as the proposal specifies; exempt from trace sampling by construction |
| Event wire schema | Attributes on LogRecords, keys exactly as in §4; `schema_version` integer, starts at 1 | Contract evolution without ambiguous parsing |
| Analyzer ingestion | Analyzer embeds an OTLP logs receiver (grpc server implementing `LogsService/Export`) | No collector dependency for the core path; an OTel Collector may be inserted later for fault injection |
| Analyzer core | Single-writer event loop (one thread owns all mutable analyzer state); ingest via bounded MPSC queue | Eliminates analyzer-internal data races by design; see §8 |
| Serialization of reports | Jackson JSON | Boring, universal |
| Evaluation analysis | Python 3.11+ (pandas, scipy) in `eval/` | Stats/CI computation |
| Testing | JUnit 5 + AssertJ; jqwik for property-based tests; Testcontainers optional for integration | See §10 |
| Formatting | google-java-format; enforced in CI | LLM-generated code from multiple devs converges on one style |
| Time source | `wall_time` from `System.currentTimeMillis()`; analyzer treats it under skew bound ε; **never used for ordering** | A4 |

Repository layout — **the repo root IS the Gradle root** (decision D1 in
`docs/decisions.md`; the proposal PDFs and deck sources stay at root, untracked build
outputs are gitignored):

```
.  (repo root = os_project)
├── settings.gradle.kts
├── gradle/libs.versions.toml   # pinned dependency versions — a contract file (§2.1)
├── .github/workflows/ci.yml    # build + format + tests on every PR
├── common/          # shared: event model, ids, schema constants, serialization (NO grpc, NO otel deps beyond API)
├── instrumentation/ # interceptors, SlotTrackingExecutor, lease scanner, priority exporter
├── testbed/         # services A/B/C, protos, workload driver, deadlock trigger, ground-truth recorder
├── analyzer/        # OTLP receiver, ingest, cut construction, graph, reduction, verdicts, coverage
├── baselines/       # 6 baselines + 3 ablation flags (ablations are analyzer config, see §9.3)
├── eval/            # run harness (bash/python), fault injection configs, analysis scripts
└── docs/            # decisions.md (append-only decision log); extra notes
```

### 2.1 Pinned dependency versions

All dependency versions live in `gradle/libs.versions.toml` and modules reference the
catalog only — **never write a version literal in a `build.gradle.kts`**. The catalog is a
contract file: changing it is a normal PR, but grpc/protobuf/OTel bumps must be tested
together (they co-constrain each other). Devs verify latest compatible patch versions once
at bootstrap (T0.1), then the catalog is authoritative.

Module dependency rule (enforced): `common ← instrumentation`, `common ← analyzer`,
`common ← testbed`, `testbed → instrumentation`. **`analyzer` never depends on
`instrumentation` or `testbed`** — it must only ever see observations, mirroring the
formal model ("the analyzer's input is a set of observations, never the system state").
Ground truth flows through a separate channel (§6.4), never through analyzer inputs.

---

## 3. System architecture

```
┌────────────────────────────  testbed  ────────────────────────────┐
│  Service A          Service B          Service C                  │
│  ┌─────────────┐    ┌─────────────┐    ┌─────────────┐            │
│  │ grpc server │    │ grpc server │    │ grpc server │            │
│  │ SlotTracking│    │ SlotTracking│    │ SlotTracking│            │
│  │ Executor(k) │    │ Executor(k) │    │ Executor(k) │            │
│  │ interceptors│    │ interceptors│    │ interceptors│            │
│  │ lease scan  │    │ lease scan  │    │ lease scan  │            │
│  └──────┬──────┘    └──────┬──────┘    └──────┬──────┘            │
│         │ OTLP logs (priority channel, unsampled)                 │
│         ▼                  ▼                  ▼                   │
│  ═══════════════ (optional fault-injection proxy) ═══════════════ │
└───────────────────────────┬───────────────────────────────────────┘
                            ▼
                   ┌─────────────────┐      ┌──────────────────────┐
                   │    Analyzer     │      │ Ground-truth recorder│
                   │ ingest → dedup  │      │ (independent side    │
                   │ → per-instance  │      │  channel from testbed│
                   │   order → cuts  │      │  internals; never an │
                   │ → graph → reduce│      │  analyzer input)     │
                   │ → verdict+core  │      └──────────┬───────────┘
                   └────────┬────────┘                 │
                            ▼                          ▼
                     verdict stream  ──────────►  eval harness (compare, score)
```

### Component responsibilities

| Component | Owns | Must not |
|---|---|---|
| `SlotTrackingExecutor` | Slot assignment, queueing, slot identity, atomic emission of wait/acquire/release | Ever assign the same slot to two executions; ever emit acquire after the task starts running |
| Client interceptor | `rpc.invocation.sent`, `rpc.block.begin/end`, invocation-id generation + metadata propagation | Block or throw into the application path |
| Server transport interceptor | `rpc.invocation.arrived` **before** executor dispatch | Depend on executor state or spans |
| Lease scanner | `execution.lease` after age threshold, then each renewal interval, while blocked | Fabricate start times (it carries the original start reference) |
| Priority exporter | Batched OTLP export on dedicated pipeline, bounded queue, drop-counting | Apply trace sampling; block application threads |
| Analyzer ingest | Dedup by `(instance_id, local_seq)`, per-instance ordering, gap tracking | Interpret wall_time for ordering |
| Cut builder | LATEST_STABLE_CUT under Δ | Include an arrival without its send |
| Graph builder | Nodes/edges from §5, over one cut | Contract resource instances away |
| Reducer + verdicts | CONFIRM algorithm, C1–C6, persistence, coverage, four-valued output | Confirm with unobserved units, unexpired gaps, or persistence < τ |
| Ground-truth recorder | Slot-level truth via direct in-process hooks in testbed; writes truth log | Share any code path with the SpanLease event pipeline |

---

## 4. Event contract — normative field spec

All events are OTel LogRecords. Attribute keys and types below are the wire contract.
`common` module defines these as constants + a typed `SpanLeaseEvent` model with
serialization both ways. **Any change to this table bumps `schema_version` and requires
sign-off from all three devs.**

Common fields (every event):

| Attribute | Type | Notes |
|---|---|---|
| `sl.schema_version` | int | 1 |
| `sl.event_type` | string | one of the 10 types below |
| `sl.instance_id` | string | stable per process run, e.g. `svcB-1` |
| `sl.local_seq` | long | per-instance monotonic, assigned under the emission lock (§7 I1) |
| `sl.causal_parent` | string | cross-process causal reference; typically `invocation_id[:peer_seq]`; empty if none |
| `sl.wall_time_ms` | long | persistence thresholds only |
| `sl.trace_id` / `sl.span_id` | string | operator correlation only; may be empty (queued invocations have no span) |

Per-type required fields:

| `sl.event_type` | Required fields |
|---|---|
| `rpc.invocation.sent` | `sl.invocation_id`, `sl.caller_execution_id` (empty if root caller), `sl.target_service` |
| `rpc.invocation.arrived` | `sl.invocation_id` |
| `resource.wait.begin` | `sl.invocation_id`, `sl.resource_id`, `sl.requested_semantics` = `"any_unit"` |
| `resource.acquire` | `sl.execution_id`, `sl.resource_instance_id`, `sl.invocation_id` |
| `rpc.block.begin` | `sl.execution_id`, `sl.invocation_id` (the awaited call) |
| `execution.lease` | `sl.execution_id`, `sl.start_ref` (the `(instance_id, local_seq)` of the acquire), `sl.observed_age_ms`, `sl.lease_expiry_ms` |
| `rpc.block.end` | `sl.execution_id`, `sl.invocation_id`, `sl.outcome` ∈ {`ok`,`error`,`deadline`} |
| `resource.release` | `sl.execution_id`, `sl.resource_instance_id` |
| `execution.end` | `sl.execution_id`, `sl.outcome` |
| `execution.cancel` | `sl.execution_id`, `sl.invocation_id`, `sl.cancel_source` ∈ {`client`,`server`,`deadline`} |

Identity formats (fixed): `invocation_id` = UUIDv4 generated at client, carried in gRPC
metadata key `sl-invocation-id` (+ `sl-caller-instance` and `sl-caller-seq` for
`causal_parent`); `execution_id` = `{instance_id}:{acquire local_seq}`;
`resource_instance_id` = `{instance_id}:{resource_id}:slot-{i}`, i ∈ [0, k).

Config surface (per service, one flat config class, no frameworks): `instance_id`,
`resource_id`, capacity `k`, lease age threshold, lease renewal interval, analyzer OTLP
endpoint, export batch size/interval, bounded queue size. Analyzer config: τ, Δ, ε,
evaluation cadence, ablation flags.

### 4.1 Parameter defaults (normative — every knob has exactly one default)

Evaluation sweeps these; code defaults are below. Constraints in the last column are
checked at startup (fail fast on violation).

| Parameter | Default | Constraint |
|---|---|---|
| τ (persistence threshold) | 2000 ms | ≥ 4 × eval interval |
| Δ (delivery delay bound) | 1000 ms | ≥ 4 × export flush interval |
| ε (clock skew bound) | 50 ms | > 0 |
| Analyzer eval interval | 250 ms | — |
| Lease age threshold | 500 ms | — |
| Lease renewal interval | 500 ms | — |
| Lease expiry_factor | 3 | ≥ 3 (tolerates one missed renewal + GC pause, R8) |
| Export batch size / flush interval | 100 events / 50 ms | — |
| Exporter bounded queue | 8192 events | drop + count on overflow (I4) |
| Analyzer ingest MPSC queue | 65536 events | — |
| Testbed executor capacity k | 2 per service | sweep {1, 2, 4} in eval |
| Testbed RPC deadline | 30 s | ≫ τ + Δ + 2ε (else no intervention window exists) |
| Workload driver seed | 42 | every run logs its seed |

Derived sanity: L1 promises detection for deadlocks persisting > τ + Δ + 2ε = 3.1 s at
defaults, against a 30 s deadline — intervention window ≈ 27 s.

---

## 5. Formal model implemented by the analyzer

### 5.1 Precedence relation →

Transitive closure of two generators:
1. **Local order:** events e, f on the same instance: e → f iff `local_seq(e) < local_seq(f)`.
2. **Message order:** for invocation I: `sent(I)` → `arrived(I)`; symmetrically the server's
   terminal response event precedes the client's `rpc.block.end(I)`.

A set of per-instance local states is a **candidate global state (consistent cut)** iff for
every included arrival, its send is included. Edges are admitted into an evaluation only if
simultaneously valid at some such cut. This — not leases — is what excludes phantom cycles;
leases only bound staleness.

Per-instance sequence numbers establish **local order only**. Never use them, or wall
time, for cross-host ordering.

### 5.2 Graph G(c) over a candidate global state c

Nodes: executions e (acquire with no causally later release/end), queue entries q
(arrived + wait.begin, no causally later acquire/cancel), resource instances u (declared
capacity + observed identity). Edges:

- `e → q` or `e → e'`: e has open `rpc.block.begin` (no causally later block-end / cancel /
  end), joined to the awaited invocation's server-side counterpart via `invocation_id`.
- `q → R`: queue entry awaits **any** unit of resource type R.
- `u → e`: unreleased `resource.acquire` **plus a live lease** (unexpired at c).

No contraction to a service-level or execution-only graph — resource instances stay
first-class (this is what makes k > 1 correct).

### 5.3 Predicate — confirmed terminal set (C1–C6)

S (executions + queue entries) is a confirmed terminal set at cut c iff:

- **C1** every execution in S is blocked at c (open block.begin, no causally later end/cancel/release).
- **C2** every queue entry in S has an open wait.begin (no causally later acquire/cancel).
- **C3 (capacity closure — the heart):** for each queue entry q ∈ S waiting on type R:
  **every** unit u ∈ R is owned at c, and every owner of every such unit is itself in S.
  If any unit of R is free or owned outside S → not terminal.
- **C4** for each blocked execution e ∈ S, the counterpart of its awaited invocation is also in S.
- **C5** ownership non-preemptive; no progress event for any member at or before c.
- **C6** C1–C5 hold continuously over a persistence interval ≥ τ, computed with skew
  uncertainty expansion (§5.5).

C3 replaces cycle detection with a closure test over units; a directed cycle among
services with spare units is explicitly **not** terminal.

### 5.4 CONFIRM algorithm (from proposal §8.3 — implement as written)

```
CONFIRM(observations O, threshold tau, delay bound Delta):
  c  <- LATEST_STABLE_CUT(O, Delta)        # consistent cut, all deps delivered
  G  <- BUILD_GRAPH(O, c)                  # exec, queue, unit nodes; live edges only
  B  <- { blocked executions and waiting queue entries at c }
  S  <- B
  repeat                                    # iterative multi-instance reduction
    removed <- false
    for x in S:
      if CAN_PROGRESS(x, S, G): S <- S \ {x}; removed <- true
  until not removed
  if S is empty:              return CONFIRMED_NO_DEADLOCK
  if COVERAGE(S, O) < full:   return CANDIDATE_INCONCLUSIVE     # A1/A2 gap
  if PERSISTED(S, tau):       return CONFIRMED_DEADLOCK, CORE(S, G)
  else:                       return CANDIDATE_INCONCLUSIVE

CAN_PROGRESS(x, S, G):
  if x is queue entry waiting on type R:
      return exists u in R such that u is free at c, or owner(u) not in S
  if x is blocked execution:
      return counterpart(awaited_invocation(x)) not in S
```

Use a worklist implementation (re-examine only members whose neighbourhood changed).
Complexity budget: O(n·(m + n·k_max)) worst case; space O(n + m + total units).

**Partially observed ownership:** a unit with no observed owner AND no observed free
state (lost acquire, or A1 violation) makes C3 unevaluable. Never treat it as free (would
suppress a real deadlock) nor as held-in-set (would fabricate one). Return
`CANDIDATE_INCONCLUSIVE` naming the unaccounted units.

**Evidence core:** the surviving S plus witnessing ownership/wait edges. Irreducible by
construction; **not** claimed minimum-cardinality.

### 5.5 Persistence under bounded skew

An interval observed as [a, b] on a remote host is treated as [a−ε, b+ε] when compared
across hosts; the intersection test uses the conservative **narrowed** overlap. C6 requires
the narrowed intersection to span ≥ τ. Ordering conclusions never depend on wall_time.

### 5.6 LATEST_STABLE_CUT

The analyzer advances a per-instance frontier: the highest `local_seq` such that all
events ≤ it have arrived (no gaps) — a gap that persists beyond Δ (measured by analyzer
receipt time) is recorded as a **coverage hole**, and the frontier does not advance past
it for confirmation purposes. The cut is the largest frontier vector closed under the
send→arrival rule: if `arrived(I)` is inside the cut, `sent(I)` must be too; otherwise
retract the arrival's instance frontier below that arrival. Deterministic given the
observation set — this is what makes replay testing possible (§10).

---

## 6. Component designs

### 6.1 `SlotTrackingExecutor` (instrumentation)

Wraps a fixed pool of k worker slots. **The decision "assign a free slot" vs "enqueue" is
made atomically under one lock** with the corresponding event emission's `local_seq`
assignment (see invariant I1) — this kills the TOCTOU race where a slot frees between the
"no free slot" check and `resource.wait.begin` emission.

States per submission: ARRIVED → (QUEUED →) ASSIGNED → RUNNING → TERMINAL(END|CANCELLED).
Slot free-list managed under the same lock. `resource.acquire` is emitted (seq assigned)
*before* the task is handed to the worker thread; `resource.release` is emitted when the
slot returns to the free list, strictly after the terminal event of the execution
(program order on the same instance ⇒ causal order).

Cancellation of a queued entry removes it from the queue and emits `execution.cancel`
under the same lock (no execution_id exists yet → use a queue-entry cancel form: the
`sl.execution_id` field carries the empty string and `sl.invocation_id` identifies it —
analyzer treats a cancel with empty execution_id as closing the queue entry).

### 6.2 Interceptors

- **Client:** grpc `ClientInterceptor` on the blocking stub channel. Generates
  `invocation_id`, writes metadata, emits `sent`; wraps the blocking call so
  `rpc.block.begin` is emitted immediately before parking and `rpc.block.end` in a
  `finally` with outcome mapping (OK/error/DEADLINE_EXCEEDED). If the caller is itself a
  server execution, the current `execution_id` is read from a Context key installed by the
  executor; root callers (workload driver) emit empty `caller_execution_id`.
- **Server transport:** grpc `ServerInterceptor` registered first, emitting
  `rpc.invocation.arrived` in `interceptCall` — before the handler, before any executor
  dispatch, before any span exists. This is the P1 exit criterion and the primary
  integration risk: verify in tests that a fully queued invocation (k saturated) produces
  `arrived` + `wait.begin` with no execution and no span.

### 6.3 Lease scanner

One daemon thread per instance, tick = min(age threshold, renewal interval)/2. Scans a
registry of live executions with open blocks. Emits `execution.lease` when
`observed_age ≥ age_threshold` and then every renewal interval while blocked, carrying
`start_ref` and `lease_expiry_ms = wall_time + renewal_interval × expiry_factor`
(expiry_factor default 3 — tolerates one missed renewal + jitter/GC pause; a lease that
expires only makes the analyzer *less* willing to confirm, which is the safe direction).

### 6.4 Ground-truth recorder (testbed only)

In-process hooks on the testbed's executors and stubs (NOT the SpanLease pipeline): every
slot assignment/release and every block/unblock appended to a local truth log
(instance-local file, monotonic per-instance counters, fsync'd per batch). A post-run
merger derives the ground-truth deadlock onset and the exact member set (executions +
units). The trigger for the deterministic deadlock (a workload pattern A→B→C→A with
saturating parallel calls, plus barrier-controlled timing so onset is reproducible under a
seed) is part of the testbed. Exit criterion P0: truth identifies exact executions and
units with zero reliance on SpanLease events.

### 6.5 Analyzer service

Pipeline (single process):

```
OTLP LogsService/Export (grpc, N netty threads)
   → parse+validate to SpanLeaseEvent (reject unknown schema_version > supported)
   → bounded MPSC queue (backpressure: block export RPC briefly, then NACK partial)
   → CORE THREAD (single writer):
        dedup (instance_id, local_seq) → per-instance ordered buffers → frontier/gap
        tracking → on evaluation tick: cut, graph, CONFIRM, persistence tracking,
        verdict emission (JSON to stdout + file + optional HTTP endpoint /verdicts)
```

Evaluation cadence: every `eval_interval` (default 250 ms) and on-demand via admin
endpoint. Persistence tracking: the core thread keeps the currently-surviving set S with
its first-confirmed-at and last-confirmed-at cut timestamps (skew-expanded); a change in S
membership resets the interval.

---

## 7. Concurrency, races, and edge cases (normative — tests must cover each)

Instrumentation-side invariants:

- **I1 (atomic seq+state):** `local_seq` is assigned under the same lock as the state
  transition it describes. Consequence: per-instance seq order = real local order. Never
  emit an event for a transition outside its critical section.
- **I2 (acquire-before-run):** `resource.acquire`'s seq is assigned before the execution's
  task can emit anything (program order). Analyzer may then rely on: any event carrying an
  execution_id is causally after its acquire.
- **I3 (release-after-terminal):** `resource.release` seq is after `execution.end`/`cancel`
  seq on the same instance.
- **I4 (no emission on the hot path blocks):** the exporter queue is bounded and
  non-blocking for the app thread; on overflow, increment a drop counter and emit a
  `coverage`-visible drop marker at next opportunity. Dropping is allowed (A3/A6 model
  it); silently blocking the app is not — it would perturb the condition being observed
  (observer effect is a measured experiment).
- **I5 (metadata before send):** invocation metadata (`sl-invocation-id`, causal parent)
  is attached before `sent` is emitted; both endpoints must report the same id.

Race matrix the design explicitly handles:

| # | Race / edge case | Handling |
|---|---|---|
| R1 | Slot frees between "queue it" decision and wait.begin emission | Impossible by I1: decision + emission are one critical section |
| R2 | Response arrives concurrently with client deadline firing | grpc delivers exactly one terminal outcome to the blocking call; `rpc.block.end` emitted once in `finally` with that outcome. If a cancel event also fires (deadline propagation), analyzer takes the causally-first terminal event as closing the block; later duplicates of *different* types for the same block are logged as contract anomalies |
| R3 | `execution.cancel` for a queued entry vs concurrent slot assignment | Same executor lock arbitrates: exactly one of {acquire, cancel} wins and is emitted |
| R4 | Duplicate delivery of any event | Idempotent dedup on `(instance_id, local_seq)` at ingest |
| R5 | Reordering across instances / within OTLP batches | Analyzer orders per instance by local_seq; cross-instance order only via → |
| R6 | Lost acquire → unit has no observed state | C3 unevaluable → `CANDIDATE_INCONCLUSIVE` naming the unit (never free, never held) |
| R7 | Lost release/end (A6) → phantom "held forever" | Lease expiry bounds staleness: ownership edge requires a live lease; expired → edge dropped → inconclusive, not false confirm |
| R8 | GC pause delays lease renewal | expiry_factor ≥ 3 renewal intervals absorbs it; a genuinely expired lease degrades toward inconclusive (safe direction) |
| R9 | Clock skew up to and beyond ε | Persistence uses narrowed-overlap intersection with ±ε expansion; beyond-ε skew is a fault-matrix case whose expected outcome is inconclusive/delayed confirmation, never false confirmation |
| R10 | Events assembled from different times forming a cycle that never coexisted (phantom) | Consistent-cut admission (§5.1); causality-free ablation exists precisely to show this failing |
| R11 | Analyzer ingest contention | Single-writer core thread; producers only touch the MPSC queue |
| R12 | Frontier stuck on a permanently lost event | Gap older than Δ (by receipt time) → recorded as coverage hole; confirmation involving that instance's affected window is barred; verdicts degrade to inconclusive with the gap named |
| R13 | Instance crash mid-run | Its events stop; leases expire; ownership edges lapse → inconclusive for affected sets. Fault matrix covers this |
| R14 | Two executions report blocks on the same invocation_id (propagation bug, A2 violation) | Contract violation counter + coverage report entry; affected edges quarantined (unattributable), verdict at most inconclusive |
| R15 | seq wraparound / restart reusing instance_id | instance_id includes a run nonce (`svcB-1@{startup_epoch}`); analyzer treats unknown epoch as a new instance |
| R16 | Cancellation races between server-side cancel and client block.end | Both may arrive; block closed by causally-first terminal; execution closed by its own terminal; reducer conditions C1/C2 consult causally-later-closure per §5.2, so either suffices to discharge membership |

Conservative-direction rule (umbrella): **any ambiguity resolves toward
`CANDIDATE_INCONCLUSIVE`/`INSUFFICIENT_OBSERVABILITY`, never toward either confirmed
verdict.** Both confirmed verdicts require complete evidence: `CONFIRMED_NO_DEADLOCK`
requires the reduction to empty the set using only *observed* frees/outside-owners —
if discharge depends on an unobserved unit, the result is inconclusive, not no-deadlock.

---

## 8. Verdict output format (JSON, stable interface for eval)

```json
{
  "verdict": "CONFIRMED_DEADLOCK",
  "schema_version": 1,
  "evaluated_at_wall_ms": 1725600000123,
  "cut": {"svcA-1@e1": 4211, "svcB-1@e1": 3980, "svcC-1@e1": 4102},
  "persistence": {"first_ms": 1725599999800, "last_ms": 1725600000100, "narrowed_span_ms": 300, "tau_ms": 250},
  "core": {
    "executions": [{"id": "svcA-1@e1:1042", "blocked_on_invocation": "…"}],
    "queue_entries": [{"invocation_id": "…", "waiting_on_resource": "svcA-1:exec-main"}],
    "units": [{"id": "svcB-1:exec-main:slot-0", "owner": "svcB-1@e1:977", "lease_expiry_ms": 1725600000900}],
    "edges": [{"from": "…", "to": "…", "kind": "wait|own|queue-wait"}]
  },
  "coverage": {
    "resource_types_instrumented": ["svcA-1:exec-main", "…"],
    "units_with_observed_state": 9, "units_total": 9,
    "unaccounted_units": [], "frontier_gaps": [], "unmatched_edges": 0,
    "outstanding_observations": []
  }
}
```

Inconclusive verdicts populate `unaccounted_units` / `frontier_gaps` /
`outstanding_observations` — the report must *name the missing item* (that's what makes
`CANDIDATE_INCONCLUSIVE` actionable and distinct from `INSUFFICIENT_OBSERVABILITY`).

---

## 9. Baselines, ablations, evaluation

### 9.1 Baselines (each runs on identical workload + ground truth)
1. **Metrics-only saturation detector** — periodic executor depth/in-flight counts
   (custom/JMX-derived; do **not** attribute such a metric to OTel RPC semconv). Expected:
   detects pressure, cannot name the set (discriminates RQ3).
2. **Completed-trace + RPC-timeout diagnosis** — status quo; establishes the
   detection-latency gap.
3. **In-progress spans → same analyzer** — strongest baseline; isolates the event
   contract's value from live-span visibility by holding the analyzer constant.
4. **Cheriton–Skeen-style RPC wait-for monitor** — invocation-level wait-for, no capacity
   model.
5. **DDMon-inspired observer** — passive observing variant.
6. **Triggered buffering (Hindsight-style)** — retrospective materialisation on trigger,
   where feasible.
(Perfect-information oracle = ground truth itself; never a competitor.)

### 9.2 Ablations (analyzer config flags — same binary)
- `--ablate-capacity`: C3 → ordinary cycle detection on contracted graph. Expect false
  confirmations on cyclic patterns with spare capacity.
- `--ablate-leases`: ownership edges never expire. Expect false confirmations from stale
  ownership after lost terminal events.
- `--ablate-causality`: assemble edges by wall-clock proximity, skip cut admission.
  Expect phantom confirmations under delay/reordering.

### 9.3 Workload & fault matrix

Dimensions: cycle length {2,3,5} services; capacity k ∈ {1, >1} per service; replication
{1, multiple instances}; load {below, at, above saturation}; hard negatives (slow-but-
progressing deps, transient saturation, **partial pool exhaustion**, cyclic patterns with
spare capacity, retries, hedged calls, cancellation races, GC pauses, jitter); failure
negatives (crashes, partitions, dropped `execution.end`/`resource.release`); telemetry
stress (loss sweep, bounded/unbounded delay, reorder, duplicate, skew ≤ε and >ε);
coverage stress (cycle through an uninstrumented resource → must yield
`INSUFFICIENT_OBSERVABILITY`).

### 9.4 Metrics
- **Timeliness:** detection latency from ground-truth onset; deadline budget saved;
  fraction detected inside intervention window.
- **Localisation:** node/edge precision+recall at execution and slot granularity;
  exact-core rate; Jaccard vs ground-truth set.
- **Correctness under uncertainty:** false-confirmation rate with a **one-sided upper
  confidence bound even at zero observed events**; inconclusive rate by cause;
  observability coverage.
- **Cost:** CPU, memory, throughput, p99 latency (with/without instrumentation — the
  observer-effect experiment is required, not optional), telemetry bytes/request,
  analyzer throughput and scaling in instances/units.

Statistical discipline: repetition counts from precision analysis targeting a stated CI
half-width (not a fixed count); power analysis for the headline comparisons; every point
estimate with a CI; seeds + configs + scripts published.

---

## 10. Testing strategy

| Layer | What | Tooling |
|---|---|---|
| Unit | Executor state machine, seq/lock invariants I1–I5, interceptor outcome mapping, dedup, frontier/gap logic, persistence arithmetic (±ε narrowing) | JUnit 5 + AssertJ |
| Property-based | (a) Random event permutations/duplications of a valid history → analyzer verdict invariant under reordering of concurrent events; (b) random loss → never upgrades a verdict (monotone: less evidence ⇒ same-or-lower on the lattice); (c) generated deadlock/no-deadlock histories → CONFIRM matches an oracle predicate evaluator | jqwik |
| Golden replay | Canned observation logs (checked into `analyzer/src/test/resources/replays/`) with expected verdict JSON; deterministic because LATEST_STABLE_CUT is deterministic given the observation set | JUnit |
| Concurrency | jcstress-style stress on `SlotTrackingExecutor` (no double-assign, no lost release, seq gap-free per instance); race matrix R1–R16 each has at least one targeted test | JUnit stress + jcstress (executor only) |
| Integration | Full testbed + analyzer in-process (multi-instance on localhost); deterministic trigger → `CONFIRMED_DEADLOCK` with exact core; spare-capacity cycle → `CONFIRMED_NO_DEADLOCK`; uninstrumented-resource route → `INSUFFICIENT_OBSERVABILITY` | JUnit + Gradle `integrationTest` source set |
| Fault injection | Loss/delay/reorder/dup/skew injected at a proxy shim in front of the analyzer's OTLP receiver (deterministic, seeded) — not with external chaos tooling | eval harness |

**The two litmus tests every change must keep green:**
1. Deterministic trigger scenario ⇒ `CONFIRMED_DEADLOCK`, core == ground truth.
2. Cyclic-call-with-spare-capacity scenario ⇒ `CONFIRMED_NO_DEADLOCK`.

---

## 11. Milestones (phases; expanded into tasks in `tasks.md`)

| Phase | Deliverable | Exit criterion |
|---|---|---|
| P0 | Bounded-executor testbed + deterministic trigger + ground-truth recorder | Deadlocks on demand; truth names exact executions+units, independent of SpanLease |
| P1 | Transport-layer arrival interception (`arrived`, `wait.begin` before dispatch) | Queued invocation observable with no execution and no span |
| P2 | Slot-level acquire/release with stable `resource_instance_id` | Reconstructed occupancy matches executor's own accounting for the whole run |
| P3 | Invocation identity propagation + client block boundaries | Client wait edges join server counterparts on `invocation_id`; zero unmatched edges nominally |
| P4 | Lease scanner + priority OTLP channel | Event volume bounded and characterised vs threshold/interval |
| P5 | Analyzer (cuts, graph, reduction, coverage, verdicts) | Reproduces ground truth on P0 testbed; `CONFIRMED_NO_DEADLOCK` on spare-capacity cycles |
| P6 | Baselines + ablations | Every baseline runs on identical workload + ground truth |
| P7 | Reproducibility package | Every reported figure reproduces on a fresh environment |

Ordering rationale: P0–P2 resolve the two highest-risk integration questions (pre-span
queue observability; slot-accurate accounting) before any analyzer work depends on them.
