# SpanLease — Technical Design

Revision: 2026-09-19. Implements the revised `requirements.md`.
**Contract v2 is specified here for review; G0 all-developer approval is still pending.**
Do not implement a mixture of v1 fixtures and v2 semantics or claim this design is proved.

## 1. Architecture and scope

The monitored resource is a fixed-capacity **application handler executor**, not the
generic executor to which grpc-java schedules transport callbacks. Each service has:

1. A gRPC server with a separate callback executor; callbacks never run blocking
   application work on transport threads.
2. An early `ServerStreamTracer.Factory` hook recording received request headers and
   invocation identity. Copy required metadata; never retain the mutable headers object.
3. A unary service adapter that receives the decoded request, submits one job to
   `SlotTrackingExecutor`, and returns. Admission is before the application span.
4. k named application worker slots. A job retains its slot throughout nested blocking
   RPC calls and cleanup, until the job actually exits.
5. A blocking-call helper around generated blocking stubs, metadata interceptors,
   response-close interceptor, lease/checkpoint scanner and dedicated OTLP exporter.

This explicitly changes the deployment model from transparent wrapping of arbitrary
gRPC callback Runnables. B-FEAS must validate this adapter with the pinned grpc-java
version before G0. Arrival is transport receipt; queue wait starts only when a decoded
job is ready and cannot obtain a slot. An arrived but not ready invocation is unresolved,
not an assumed queue entry or progressing counterpart.

The analyzer consumes observations plus an immutable run manifest and recorded timing/
health context. It never consumes testbed truth. A separate evaluator compares verdicts
with independent truth after the run.

## 2. Stack, modules and ownership

Java 21, Gradle Kotlin DSL, grpc-java blocking unary stubs, OTel SDK LogRecords over
OTLP/gRPC, JUnit 5, AssertJ, jqwik, Jackson, google-java-format. No Spring, Lombok,
direct Guava use, or extra dependencies without a reviewed design/catalog change.
All versions live in `gradle/libs.versions.toml`; bootstrap checks compatibility,
not “latest” claims. Python evaluation dependencies must also be pinned when added.

| Module | Owner | Responsibility / allowed dependencies |
|---|---|---|
| `common` | B event types; C reports; joint review | Immutable domain/wire DTOs, constants, IDs, validation; JDK/Jackson only; no grpc, OTel SDK, testbed or truth |
| `instrumentation` | B | Gate, blocking helper, stream/metadata hooks, LogRecord encoding, scanners/exporter; depends on common |
| `testbed` | A | Generic Chain service, driver, independent truth, scenarios; depends on common and instrumentation |
| `analyzer` | C | OTLP decoder/receiver, single-writer state, certified cuts, reduction, reports; depends on common, never instrumentation/testbed |
| `baselines` | C coordination; A/B assigned tasks | Evaluation-only monitors/adapters; never imported by analyzer production code |
| `eval` | A runs; C statistics; B packaging | Fault schedules, manifest, observations/truth joining, figures and reproduction |
| `docs` | all | Decisions, proof/validation notes and source-backed literature |

Repo root is Gradle root. No Java implementation/wrapper exists at this revision.
`./gradlew build` and `./gradlew integrationTest` become acceptance commands after
A-BOOT. Bootstrap CI guards must not be mistaken for executed tests.

## 3. Identities and local state

- `instance_id = service-replica@UUID` is unique per process epoch, including driver.
- Invocation IDs are UUIDv4, one per application attempt, shared at both endpoints.
  Deterministic tests inject an ID source; production uniqueness is not a seeded-RNG claim.
- `execution_id = instance_id:acquire_seq`.
- `resource_id` is an instance-local pool name; manifest keys include instance_id.
- `resource_instance_id = instance_id:resource_id:slot-i`, i in [0,k).
- Event reference is `instance_id#local_seq`; sequence starts at 1, never reused.
- One outstanding block per execution; one current execution per admitted invocation.
  All ID joins also validate epoch, ownership and manifest membership.

State machines:

- Invocation: ARRIVED → READY → QUEUED or ASSIGNED → RUNNING → ENDED.
  QUEUED can become CANCELLED without an execution. Rejection before assignment is
  explicitly recorded. Arrival before READY is never interpreted as slot contention.
- Execution: ASSIGNED → RUNNING ↔ BLOCKED → ENDED; cancellation is an orthogonal flag.
  A cancelled running job can remain physically running and owning its slot.
- Unit: observed FREE → HELD(execution) → FREE. Missing evidence produces UNKNOWN
  in the analyzer, not a fabricated transition in the application.

## 4. Version 2 observation contract

**Approval gate:** this is a proposed replacement for v1, requiring a contract-tagged
PR, A/B/C sign-off and migration of fixtures before implementation freeze. Thirteen
record types use the same unsampled LogRecord pipeline. No hidden control channel or
ground-truth input supplies missing evidence.

Common attributes on every record:

| Key | Type | Rule |
|---|---|---|
| `sl.schema_version` | int | 2; reject unsupported versions, never reinterpret v1 |
| `sl.event_type` | string | One of the thirteen types below |
| `sl.instance_id` | string | Manifest process epoch |
| `sl.local_seq` | long | Positive; allocated with the local transition |
| `sl.causal_parent` | string | Exact event reference or empty |
| `sl.wall_time_ms` | long | Reference-qualified physical timestamp |
| `sl.monotonic_time_ns` | long | Process-local elapsed-time source, never compared across hosts |
| `sl.trace_id`, `sl.span_id` | string | Operator correlation or empty; not identity/evidence substitutes |

All per-type fields below carry the `sl.` prefix. String unless a type is specified.
Omitted optional values are absent, not invented; explicitly empty identity fields
below mean no execution exists. LogRecord native trace correlation may additionally
be populated consistently, but is not the authoritative join key.

| Type | Required fields and semantics |
|---|---|
| `resource.init` | resource_id, capacity:int, unit_ids:string[], initial_state=`free`; once per pool before admission; IDs must exactly match manifest |
| `instance.checkpoint` | through_seq:long = its own local_seq−1, dropped_total:long; serialized boundary after all prior transitions/offers; emitted even when idle |
| `rpc.invocation.sent` | invocation_id, caller_execution_id (empty for driver), target_service; committed before actual transport send |
| `rpc.invocation.arrived` | invocation_id; causal_parent is exact request sent ref copied from metadata; receipt before application admission |
| `resource.wait.begin` | invocation_id, resource_id, requested_semantics=`any_unit`; ready job queued atomically with event |
| `resource.acquire` | execution_id, resource_instance_id, invocation_id; slot assigned before job can run |
| `rpc.block.begin` | execution_id, invocation_id; logical entry to blocking-call helper; may precede sent |
| `execution.lease` | execution_id, invocation_id, start_ref (acquire), block_ref (current block.begin), observed_age_ms:long (block age), lease_expiry_ms:long |
| `rpc.response.sent` | invocation_id, execution_id (empty for pre-admission rejection), outcome in {ok,error,cancelled,deadline}; committed at terminal response-close boundary before transport close |
| `rpc.block.end` | execution_id, invocation_id, outcome in {ok,error,cancelled,deadline}, completion_kind in {response,local}; response completion has exact response.sent causal_parent; local completion has empty parent |
| `execution.cancel` | execution_id (empty before assignment), invocation_id (inbound invocation), cancel_source in {client,server,deadline}, cancel_reason in {requested,deadline,rejected}; cancellation/rejection observation, not release |
| `execution.end` | execution_id, invocation_id (inbound), outcome in {ok,error,cancelled,deadline}; actual job exit, including exceptional paths |
| `resource.release` | execution_id, resource_instance_id; actual slot returned after end |

Request metadata: `sl-invocation-id`, `sl-caller-instance`, `sl-caller-seq`.
Terminal response trailers: `sl-response-instance`, `sl-response-seq`.
Validate lengths/formats before using them; malformed/duplicate identity is a named
coverage defect. The client records response trailers before helper return. A positively
identified local transport failure/deadline/cancel has no fabricated response parent.
Missing trailers alone do not prove local completion: a remote error can also have
missing metadata. Unknown completion origin is invalid evidence; B-FEAS must demonstrate
the supported classification. Successful responses without required parents are invalid.

The driver emits sent/checkpoint records but no execution-scoped block events. If a
root response can causally trigger later root sends, carry the response ref as the
later sent's causal_parent; otherwise that workload has an unrepresented causal edge.
Likewise represent any application coordination messages that could affect monitored
waits, or keep them before the measured interval. Barrier synchronization is not an
unobserved resource inside a claimed closed set.

### 4.1 Defaults and validation

Preserve previous defaults unless listed as newly introduced v2 settings.

| Parameter | Default | Validation |
|---|---|---|
| τ | 2000 ms | >0 and ≥4×evaluation interval |
| Δ | 1000 ms | >0 and ≥4×export flush interval |
| ε | 50 ms | >0; reference qualification recorded |
| Evaluation interval | 250 ms | >0 |
| Lease block-age threshold / renewal | 500 / 500 ms | >0 |
| Lease expiry factor | 3 | ≥3; not a GC-tolerance guarantee |
| Scanner tick | 250 ms | min(age threshold, renewal)/2; positive |
| Checkpoint interval (new) | 500 ms | >0; maximum scheduling delay measured separately |
| Export batch / flush | 100 records / 50 ms | >0 |
| Export queue | 8192 records | nonblocking offer; loss visible via sequence gap |
| Analyzer ingress queue | 65536 records | bounded batch admission |
| Gate slots k | 2 | positive; evaluate 1,2,4 |
| Gate waiting capacity (new) | 4096 jobs | positive; reject overflow promptly |
| Testbed deadline | 30 s | positive; compare against measured detection budget |
| Driver seed | 42 | recorded; RNG injected |

No numeric detection guarantee follows from these defaults. A 30-second root deadline
does not mean every nested call has 30 seconds remaining.

### 4.2 Manifest, envelopes and compatibility

Immutable run manifest: run_id, manifest_hash, list of instance epochs (including
driver), service binding, each pool's IDs/capacity/instrumented flag, supported
call policy, schema version, parameters, and clock-qualification intervals. It contains
configuration only, never current slot ownership or truth labels. Manifest changes
start a new run. Missing clock qualification prevents confirmation.

Observation envelope: decoded event plus recorded analyzer receipt wall/monotonic time.
Evaluation context: run/manifest identity, ordered received envelopes, evaluation wall/
monotonic time and configuration. Use virtual context for replay. Preserve conflicting
duplicates as anomalies, not “first payload wins.” Identical duplicates are idempotent.

The existing YAML schema and worked v1 log are not valid v2 confirmation fixtures.
C-CONTRACT migrates them explicitly, including init/checkpoints, response parents,
timestamps and evaluation schedule. Do not add a YAML dependency implicitly; use JSON
fixtures with Jackson unless G0 explicitly approves a YAML parser and catalog entry.

## 5. Analyzer semantics

### 5.1 Ingestion and certification

Only the core event-loop thread mutates state. Decode/validate at ingress, atomically
admit bounded batches, deduplicate by instance/seq, and buffer by local sequence.
Unsupported schema, conflicting payloads, impossible transitions and identity aliases
are quarantined and invalidate affected run coverage.

A checkpoint certifies only a **received contiguous prefix starting at 1**, including
all initialization records and the checkpoint itself. A checkpoint beyond a missing
sequence does not skip or repair it. Missing suffixes become visible because new
checkpoints continue during idle periods. No checkpoint/no progress is insufficient
evidence, even if Δ has elapsed. dropped_total is diagnostic; it never fills a gap.

For the first implementation, a permanent gap blocks further certification of that
instance in the run; continue reporting the cause. No snapshots, skip-ahead recovery
or retransmission log is required. A new clean run is needed to recover after permanent
loss. This conservative availability cost must be measured.

### 5.2 Consistent cut and scope

Evaluate the whole manifest scope. Start from the latest certified checkpoint prefix
for each instance. Retract any prefix containing a causal child without its parent,
and iterate to a fixed point. Apply this to **all** nonempty causal_parent fields,
including responses; a send without its receive is valid. Parent references must point
to valid event types/invocations; cyclic/contradictory causal metadata is invalid.

Later certified observations remain available to bound intervals even when a candidate
prefix is retracted. A checkpoint is a local completeness boundary, not a simultaneous
global snapshot. Physical timestamps do not choose causal edges or justify their order.
Report the exact resulting vector; no global “no deadlock anywhere ever” interpretation.

Require recent per-instance checkpoint receipt for online eligibility: at evaluation,
age since its last newly certified, advancing checkpoint receipt must be at most checkpoint_interval + Δ +
evaluation_interval + 2ε, with configured scheduler/processing allowances from §5.7
added when a bounded-latency experiment uses them. An expired horizon produces an
inconclusive current query; it does not invalidate a historically justified old report.
Missing monotonic/clock qualification is never compensated by larger guessed slack.
Duplicates and later uncertifiable checkpoints cannot refresh this age. An observed
gap beyond the old certified prefix is still reported; retracting to an old cut cannot
hide a current coverage defect.

### 5.3 Graph and complete knowledge

Replay local transitions through the cut. Retain executions until release, including
ended-but-not-yet-released jobs. Only executions with an open block and no observed
cancellation enter blocked candidates. Ownership exists independently of lease state.

Wait edges join invocation IDs to the actual queued/admitted counterpart. Queue edges
name the selected instance's eligible pool. Ownership edges name each unit and owner.
Keep unresolved arrivals, unknown slots and absent counterparts as UNKNOWN.

A candidate whose dependency cannot be evaluated makes this first whole-scope query
inconclusive. Known unsupported configuration returns insufficient observability.
Neither UNKNOWN nor expired lease is a free slot or a progressing execution. A known
running/nonblocked counterpart or owner is a progress witness only under supported
scope and complete prefix evidence.

Cancellation while a helper remains blocked is unresolved eligibility, not observed
progress: return CANDIDATE_INCONCLUSIVE with CANCEL_PENDING until block.end/job exit
resolves it. Missing or expired current-block lease evidence also prevents either
confirmation in this initial conservative whole-scope implementation. A nonblocked
running owner does not need a blocked-execution lease to be an observed progress witness.

### 5.4 Reduction and verdict selection

Let B be all observed blocked executions and waiting queue entries at the cut.

```text
if deployment/identity contract is invalid: INSUFFICIENT_OBSERVABILITY
if scope/init/prefix/clock/freshness evidence is incomplete: CANDIDATE_INCONCLUSIVE
if any candidate dependency is UNKNOWN: CANDIDATE_INCONCLUSIVE

S = B
repeat:
    remove queued q if an eligible unit is observed FREE
        or its observed owner is known nonblocked or has already been removed
    remove blocked e if its observed counterpart is known nonblocked/completed
        or has already been removed
until fixed point

if S is empty: CONFIRMED_NO_DEADLOCK at this cut

P = members of S whose current required assertions have certified
    uninterrupted coverage of [b - tau, b] for a candidate endpoint b
prune P by the same closure rules, treating owners/counterparts in S\P
    as unavailable to the proposed witness (not as proof of real progress)
if P nonempty and all witness leases are eligible at b:
    CONFIRMED_DEADLOCK with evidence set P and interval [b - tau, b]
else: CANDIDATE_INCONCLUSIVE with the failed temporal/evidence condition
```

The second pruning pass searches for a temporally qualified closed subset; it must
**never** return CONFIRMED_NO_DEADLOCK. This avoids a new upstream waiter resetting an
old persistent deadlock's interval. For positive evaluation, enumerate the finite
candidate endpoints at guaranteed assertion and matching lease-eligibility interval
boundaries from §5.5 and choose
the latest qualifying b; deterministic ties use sorted IDs. Retain all qualifying
closed members at that endpoint, not a claimed minimum/irreducible witness.

Worklist reduction over explicit dependency/owner edges is the initial implementation.
Bound and measure graph/history memory; a resource limit causes a named inconclusive
result, never silent eviction followed by confirmation. No history may be discarded
until no retained query/witness or outstanding causal parent can need it. Initial
experiments are finite runs with full-history retention; streaming compaction is deferred.

### 5.5 Continuous persistence and leases

For every current wait, ownership and queue assertion, record the beginning event and
the first later closing/change event if observed; otherwise use a later certified
checkpoint on that instance as the evidence endpoint. All intervening sequences must
be present. For a real transition with observed wall time a and later certified endpoint
b, the guaranteed valid interval is [a+ε, b−ε]; intervals shorter than zero are empty.
Use half-open boundaries at terminal events to avoid asserting validity at closure.

Compute overlap by max of lower bounds and min of upper bounds. It must be at least τ.
A change of invocation, owner, eligibility or cancellation closes the corresponding
interval even if node membership is unchanged. Checking only successive sampled graphs
or letting evaluation ticks advance a stale cut is invalid.

A lease contains the exact current block_ref and is generated from an atomic snapshot.
It establishes observed blocked state at its emission, not throughout its future expiry.
Require a matching lease whose guaranteed unexpired range includes b; its emission and
expiry bounds are narrowed by ε. The uninterrupted history/checkpoint establishes the
duration. Expired leases retain ownership as known-but-ineligible evidence; they do not
create frees or discharge reduction. Root/queue liveness completeness is supplied by
checkpoints, not invented execution leases for entities without an execution.

### 5.6 Proof and replay obligations

Prove S1, S2, reduction termination and order independence for the specified model.
The persistence-qualified set must remain closed after temporal pruning. Check the
proof against two disjoint cycles, upstream dependents and same-node/new-wait cases.
Independent oracle tests must not call production graph/reduction helpers.

Replay output is a pure function of manifest, envelopes, query/evaluation context and
configuration, with sorted report arrays. Permutation invariance compares identical
delivered facts at an identical certified query, not different online arrival times.
Evidence-removal tests hold scope/cut/time fixed and forbid upgrading uncertainty into
either confirmation. No total ranking between the two confirmed verdicts is assumed.

### 5.7 Detection timing

L1 is conditional eventual detection while a qualifying wait persists. For a finite
bound define a maximum scanner scheduling delay J_s, checkpoint scheduling delay J_c,
evaluation scheduling delay J_e and analyzer processing lag P; these are experiment
assumptions/measurements, not arbitrary default guarantees.

A conservative budget to derive and test is:
`max(tau + 2*epsilon, age_threshold + scanner_tick + J_s) +
 renewal_interval + scanner_tick + J_s +
 checkpoint_interval + J_c + Delta + eval_interval + J_e + P`.
This intentionally allows a matching renewal and a subsequent complete checkpoint.
The proof must also establish that witness lease coverage and the chosen certified
horizon overlap; if that feasibility condition fails, report inconclusive. G1 reviews
the derivation before presenting it as a bound. Until then it is a budget hypothesis.

Report actual time saved relative to the earliest relevant propagated deadline. Do
not reuse the old 3.1s/27s promise. Under overload or unbounded scheduling delay there
is no finite latency guarantee.

## 6. Component implementation rules

### 6.1 Gate and synchronization

Use one instance-local short critical section for gate state, invocation/block registry,
sequence allocation, checkpoint snapshots and nonblocking publication. Do not invoke
application code, grpc send/close, serialization or network export while holding it.
Prepare immutable records and offer them in allocated order; a failed offer increments
dropped_total while preserving its allocated sequence. A later checkpoint exposes the
gap. The design permits short mutex contention, not waiting for telemetry queue space.

Assign a slot and acquire sequence before scheduling the job. The task's finally path
records end then release under the same state discipline. Exception handling cannot
leak a slot; telemetry failure cannot throw into application work. When a slot becomes
free, dequeue/assign atomically. Queue overflow rejects with an explicit pre-admission
cancel(reason=rejected) and error response; it never holds a slot.

Queued cancellation removes the queued job if assignment has not won. If assignment
wins, acquire can legitimately precede cancel; cancellation does not pretend acquisition
never occurred. Running cancellation sets a flag and requests cooperative cancellation;
it cannot release the worker until actual exit.

### 6.2 RPC hooks and Context

The blocking helper allocates invocation identity and opens block state before calling
the generated blocking stub; metadata interception uses that identity, commits sent
before dispatch, and validates no concurrent block. The helper closes the block once
in finally using actual returned outcome/received causal metadata. Root callers use
identity/metadata instrumentation without an execution block.

The early stream tracer captures arrival and propagates copied invocation context to
the unary adapter. An ordinary ServerInterceptor is not pre-dispatch arrival capture.
The response-close wrapper reserves/records response.sent and inserts its reference in
trailers before delegating close, once per invocation. Close/transport failure after
reservation does not fabricate a receive; only the client's observed metadata supplies
that relation. Preserve cancellation delivery on callback threads independent of the
blocked application workers. Scope-limited application spans start at acquire.

### 6.3 Export and receiver

Dedicated unsampled LogRecord provider/exporter, bounded queues, asynchronous export.
Do not promise reliable delivery merely because transport uses gRPC. Measure SDK queue
and retry losses as well as instrumentation queue drops. Sequence gaps reveal either.

Receiver validates then admits a full batch atomically where feasible. Retryable
transient overload uses OTLP-compatible UNAVAILABLE (or specified retry information),
not partial-success “NACK.” A populated OTLP partial-success response must not be
retried. Malformed records cause observable rejection/coverage loss; no parser crash.
Transport size/attribute limits and bounded ID parsing must be configured and tested.
Loopback is the default experiment endpoint; remote experiments use authenticated TLS.
Do not expose an unauthenticated ingestion/admin service publicly.

### 6.4 Independent ground truth

A owns a separate recorder observing gate transitions and handler call boundaries,
including actual worker exit, without consuming SpanLease events, sequence IDs or
analyzer helpers. Use independent job/slot IDs and an evaluator-only mapping to
invocation/slot identities. This mapping never enters the analyzer.

Truth records ordering and monotonic timestamps; cross-host onset is an interval under
the same measured clock uncertainty, not an invented exact instant. Report detection
latency uncertainty. Barrier-controlled triggering coordinates before the measured
closed wait; release every test barrier before asserting that the wait is due to RPC/
slots. Bare and instrumented runs use the same gate semantics so observer-effect cost
does not compare different execution architectures.

## 7. Invariants and targeted race tests

- I1: transition, sequence allocation and record publication attempt are serialized.
- I2: acquire precedes any application job execution or execution-scoped event.
- I3: actual task exit/end precedes release; cancel observation alone never releases.
- I4: telemetry emission never waits for network/I/O or queue space; critical sections
  are short and measured. Telemetry failure cannot break application semantics.
- I5: request/response causal metadata and corresponding send record are prepared before
  transport dispatch/close; client response receipt has a valid parent when applicable.

| Row | Required targeted test | Owner tasks |
|---|---|---|
| R1 | Free-slot versus enqueue decision is atomic | B-GATE |
| R2 | Response versus deadline yields one block.end with correct response/local cause | B-RPC |
| R3 | Queued cancel versus assignment; acquire-then-cancel is legal | B-GATE, B-RPC |
| R4 | Identical duplicates idempotent; conflicting duplicate invalidates evidence | C-INGEST |
| R5 | Reorder/duplicate replay at fixed query context | C-CUT |
| R6 | Lost acquire and never-used unit remain unknown | C-CUT, C-REDUCE |
| R7 | Lost release/end, tail loss and lease expiry never create progress | B-EXPORT, C-CUT |
| R8 | Scanner versus block-end/cancel cannot renew closed block; delayed scanner degrades | B-SCAN |
| R9 | Qualified skew boundaries and unqualified clock cases distinguished | C-TIME |
| R10 | Phantom cycles; request and response parents both enforced | C-CUT, C-PROOF |
| R11 | Concurrent ingestion, batch admission and single-writer ownership | C-INGEST |
| R12 | Permanent interior gap and absent idle checkpoint block certification | C-CUT |
| R13 | Crash stops checkpoint horizon; no inferred release | A-FAULT, C-CUT |
| R14 | Alias/absent invocation propagation invalidates joins | B-RPC, C-INGEST |
| R15 | Restart epoch/sequence reuse cannot merge histories | B-SCAN, C-INGEST |
| R16 | Cancel request, block.end, handler exit and slot release remain distinct | B-RPC, C-REDUCE |

Each implementation change ships the applicable targeted test. Documentation alone
does not satisfy these obligations. Use latches/barriers/virtual time, never sleeps to
synchronize tests. Additional races extend this table and the same-change test suite.

## 8. Verdict JSON v2

Every field below is required; nullable values explicitly use null. Arrays are sorted
by identity and empty when inapplicable. This example is an inconclusive report, not
a fabricated successful result.

```json
{
  "schema_version": 2,
  "run_id": "run-42",
  "manifest_hash": "sha256:example",
  "verdict": "CANDIDATE_INCONCLUSIVE",
  "evaluated_at_wall_ms": 10000,
  "query": {
    "scope_instances": ["svcA@epoch1"],
    "cut": {"svcA@epoch1": 0},
    "certified_through_wall_ms": null
  },
  "persistence": null,
  "core": {"executions": [], "queue_entries": [], "units": [], "edges": []},
  "coverage": {
    "units_total": 2,
    "units_with_observed_state": 0,
    "unaccounted_units": ["svcA@epoch1:main:slot-0", "svcA@epoch1:main:slot-1"],
    "frontier_gaps": [{"instance_id": "svcA@epoch1", "from_seq": 1, "to_seq": 1}],
    "unmatched_invocations": [],
    "missing": [{"code": "MISSING_INIT", "subject": "svcA@epoch1:main"}, {"code": "SEQUENCE_GAP", "subject": "svcA@epoch1#1"}]
  }
}
```

Normative nested types:

- query.cut maps every scoped instance to its selected prefix (0 if none);
  certified_through_wall_ms is min checkpoint lower time bound, or null if unavailable.
- persistence, when positive, contains first_ms, last_ms, narrowed_span_ms, tau_ms
  (integers); last−first = narrowed_span ≥ τ. Otherwise null.
- core.executions: {id, invocation_id, blocked_on_invocation, block_ref}.
- core.queue_entries: {invocation_id, instance_id, resource_id}.
- core.units: {id, owner_execution_id, acquire_ref, lease_ref}; lease_ref may be null
  only in a diagnostic inconclusive core, never for a positive owner's blocked state.
- core.edges: {from, to, kind}; kind is wait, queue-wait, or own. Node identities use
  the formats in §3; queue node = invocation_id; resource node = instance_id:resource_id.
- coverage unit counts cover the whole declared scope; frontier gaps are inclusive
  ranges; unmatched_invocations and unaccounted_units are string arrays.
- missing entries have code and subject strings. Codes: UNSUPPORTED_SCOPE,
  UNINSTRUMENTED_RESOURCE, INVALID_IDENTITY, INVALID_EVENT, MISSING_INIT, SEQUENCE_GAP,
  MISSING_CHECKPOINT, STALE_CHECKPOINT, MISSING_CAUSAL_PARENT, UNKNOWN_UNIT,
  UNKNOWN_COUNTERPART, CANCEL_PENDING, EXPIRED_LEASE, UNQUALIFIED_CLOCK, PERSISTENCE_TOO_SHORT,
  RESOURCE_LIMIT. Multiple causes can coexist; deterministic classification priority
  follows §5.4. Extra operator detail belongs in diagnostics, not undocumented JSON keys.

Negative reports have empty core and null persistence. Inconclusive reports may include
a diagnostic candidate, clearly not a confirmed witness. Positive core denotes the
persistent residual set, not minimum cardinality or an irreducible root cause.

## 9. Research evaluation

Compare metrics-only pressure, completed-trace/timeout diagnosis, ordinary live spans,
live spans enriched with **identical** queue/slot facts into the same analyzer, a generic
invocation monitor, a source-verified DDMon-inspired adaptation, and Hindsight-style
triggered buffering where feasible. Label ports/adaptations; never transfer DDMon proofs
to a different observation model. Verify the Cheriton–Skeen/thesis attributions before
using those names as implemented baselines.

Sweep cycle lengths 2/3/5 by deploying the same service multiple times, k=1/2/4,
replicas with actual binding, load, deadlines, τ/lease/checkpoint intervals and faults.
Include saturation-only, slow progress, spare capacity, multiple cycles, upstream
dependents, cancellation, response-before-release, unused slots and missing suffixes.
Hedging/async/uninstrumented resources are known unsupported cases, not claimed
in-scope negatives. Add a supported application or trace-derived topology with provenance.

Capacity/lease/causality ablations change one mechanism at a time. Do not force false
positives: intact completeness guards may preserve safety in a lease-free variant.
Measure latency, localization against the matching residual-set truth, useful deadline
budget, both false-confirmation directions, inconclusive causes, CPU/memory/throughput/
p99 and telemetry bytes. Report independent-run uncertainty, not correlated ticks as
independent trials. For zero failures in N independent trials, one-sided 95% bound is
1−0.05^(1/N); no empirical “zero errors” soundness claim.

## 10. Verification and publication gates

Unit/state-machine tests, property tests, fixed-context golden replay, independent
bounded-history oracle, and real gRPC integration all serve different obligations.
The two litmus tests remain mandatory, using v2 initialization and complete checkpoints.
Raw v1 logs cannot pass a v2 confirmation test by assuming missing state.

Gate G0: supported integration proven, v2 contract/fixtures reviewed by all three.
Gate G1: independent truth and event fidelity; analyzer core and proof review.
Gate G2: real end-to-end litmus/adversarial tests and CI pass.
Gate G3: strong baselines, realistic workload, complete evaluation and reproduction.
Only then decide whether the evidence supports a full paper; otherwise use an
appropriate preliminary-results track. Current venue deadlines are not specified.

## 11. Requirement traceability

| Requirements | Implementation / acceptance |
|---|---|
| §§2–3 objective/scope | §§1,3,6; B-FEAS, A-TRIGGER |
| §4 A1–A6 | §§4.2,5.1–5.3,7; C-CUT, C-PROOF |
| §§5–6 evidence/entities | §§3–4,6; B-CONTRACT, B-GATE, B-RPC, B-SCAN |
| §7 verdicts/predicate | §§5,8; C-REDUCE, C-TIME, C-REPORT |
| §8 obligations | §§5.6–5.7,10; C-PROOF, G1/G2 |
| §9 deliverables | §§9–10; task gates G0–G3 and publication task P-PAPER |
