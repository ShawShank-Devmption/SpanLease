# SpanLease — Requirements

Revision: 2026-09-19, publication-oriented implementation baseline.

## 1. Status and document authority

SpanLease is a research project, not a validated detector. This revision resolves the
specification defects identified in `docs/technical-review.md`. Requirements state
what must hold; `design.md` defines the proposed implementation; `tasks.md` assigns
ordered work to three developers; `AGENTS.md` governs contributions.

The revision-2 proposal PDF remains the historical research motivation. Where its
semantics conflict with this explicitly revised baseline, the changes are recorded in
`docs/decisions.md`; do not reintroduce those defects. The revised event/report contract
is **version 2, pending the all-developer contract review G0**. Documentation is ready
for that review; this does not assert approval, a completed implementation, or proofs.
Existing v1 replay files remain historical fixtures until migrated at G0.

## 2. Objective and bounded contribution

Localize persistent circular waits involving synchronous RPCs and bounded application
executor capacity before RPC deadlines, using OpenTelemetry-compatible **custom
telemetry**. Return explicit missing-evidence explanations when confirmation cannot be
justified. A verdict concerns a certified historical interval, not guaranteed current
state or safe remediation.

The intended contribution is an evidence protocol, implementation and evaluation for
this setting. Distributed deadlock detection, capacity reduction, consistent cuts,
leases and live spans are prior art. Novelty must be supported by the comparisons in
`docs/literature-review.md`, particularly DDMon and enriched live spans.

Distinguish slow progress, saturation without circularity, and closed circular waiting.
For a queued request eligible for any slot in its selected pool, closure requires every
slot of that pool to be owned within the blocked set. Other pools can have spare slots.
Deadlines/cancellation can break a circular wait: do not describe finite-deadline RPC
waits as necessarily permanent deadlocks.

## 3. Supported deployment

- Java 21; blocking unary grpc-java client stubs; one outstanding downstream RPC per
  executing handler. No async/streaming/virtual-thread/actor claim.
- Explicit application admission gate with k fixed worker slots per service instance.
  gRPC transport/callback execution is separate from this monitored worker pool.
  The generated service adapter submits one unary handler job to the gate and returns
  promptly; the job can invoke downstream blocking stubs while retaining its slot.
- Instrumentation begins before application admission opens. Instances and pools are
  declared in an immutable run manifest. Mid-run attachment, resizing, and dynamic
  membership require a new epoch and are not supported in the first implementation.
- Invocation binding is to the selected instance. A free slot on a different replica
  does not satisfy a request already queued here. No implicit rerouting.
- Sequential application retries create new invocation IDs. Disable transparent retries
  and hedging in measured channels. Concurrent alternatives are unsupported.
- A logical blocking boundary surrounds the blocking-stub invocation, including its
  dispatch and return. It is not a claim to observe a JVM thread's physical park.
- An application span begins only after gate admission. Transport spans may exist
  earlier; “queued without a span” means without an application execution span.

This is an explicit deployment restriction, not transparent monitoring of arbitrary
`ServerBuilder.executor` configurations. Early transport arrival alone does not mean
the full unary request is ready for application admission.

## 4. Assumptions and limits

| ID | Assumption for confirmation | Consequence when unavailable |
|---|---|---|
| A1 | Manifest enumerates all participating instances, pools and eligible slots; relevant application dependencies use the supported gate/RPC model | Known missing instrumentation → INSUFFICIENT_OBSERVABILITY; hidden undeclared dependencies are outside the guarantee |
| A2 | Invocation and causal references are unique, correct and propagated; instrumentation reflects actual local transitions | Detected identity/state violation invalidates affected evidence; undetected instrumentation bugs are outside the guarantee |
| A3 | Required observations, including closure and checkpoint records, arrive within Δ in guarantee experiments; all allocated sequence positions through a checkpoint are received | Missing prefix/suffix evidence prevents confirmation; Δ is not itself proof of completeness |
| A4 | Timestamp error is at most ε relative to the run's agreed time reference for the evaluated interval; local durations use monotonic time | Known loss of clock qualification prevents confirmation; arbitrary undetected skew/drift has no guarantee |
| A5 | Capacity, eligible-unit membership and instance epoch are stable; startup free state is observed before admission | Unknown state remains UNKNOWN, never inferred free or held |
| A6 | State+sequence instrumentation is correct; the priority pipeline's bounded nonblocking offers preserve allocated sequence numbers even when records are dropped | Drops cause uncertifiable prefixes; leases do not repair missing terminal events |

Clock qualification and deployment configuration are recorded run evidence, not values
invented by the analyzer. Fault tests outside assumptions characterize behavior; they
cannot establish that every unobservable violation will be detected.

## 5. Required observations

Every record has a process-epoch identity, local sequence, schema version, causal
reference where applicable and physical timestamp. The full v2 field table is design
§4. Per-instance order comes from sequence numbers; cross-instance order comes from
explicit message references. Time is used for evidence freshness/persistence only.

Ten original lifecycle families are retained with clarified semantics: invocation sent
and arrived, resource wait/acquire/release, RPC block begin/end, execution lease/end/
cancel. Version 2 adds `resource.init`, `instance.checkpoint`, and `rpc.response.sent`.
These supply initial state, complete-prefix evidence, and response causality missing
from v1. Checkpoints continue even when no handler is running. Export is unsampled and
separate from ordinary trace sampling; ordinary spans never fill missing detector facts.

Cancellation is an observation of requested cancellation, not proof of task exit.
`execution.end` records actual handler-job termination for all outcomes, and release
follows actual termination. A response may precede handler termination/slot release.

## 6. Domain entities

| Entity | Meaning |
|---|---|
| Instance | One process epoch, with one ordered event stream |
| Invocation | One unary call attempt, same ID at client/server |
| Execution | One admitted handler job, created when a slot is assigned |
| Queue entry | Ready unary handler job awaiting a slot, no execution yet |
| Resource / unit | Selected instance's fixed pool / one named worker slot |
| Ownership | Observed acquire until observed release; never removed merely because a lease expires |
| Wait | Logical blocking RPC interval or queued admission interval |
| Lease | Fresh observation of the current blocking state; expiry bounds eligibility, not future lifetime |
| Checkpoint | An instance-local state-serialization boundary certifying a complete prefix only if every earlier sequence is delivered |
| Consistent cut | Vector of local prefixes closed under every included causal parent |
| Evidence set | Closed surviving blocked set and its witnessing slots/edges; may include dependents and multiple cycles; no irreducibility claim |

## 7. Predicate and verdicts

A nonempty set S qualifies only if all its executions are observed blocked, all queue
entries are observed waiting, every blocked execution's counterpart belongs to S, and
every eligible slot of every queued member's pool is held by an execution in S. Those
same dependencies must hold without interruption for a guaranteed overlap of at least
τ. The set is evaluated at a consistent cut with complete evidence and qualified clocks.

| Verdict | Meaning |
|---|---|
| CONFIRMED_DEADLOCK | A covered closed evidence set satisfies the predicate throughout the reported interval |
| CONFIRMED_NO_DEADLOCK | Complete evidence for the declared query scope at the reported cut permits observed-progress reduction to empty the candidate set; not a whole-run or future guarantee |
| CANDIDATE_INCONCLUSIVE | Transient missing/late/expired evidence, uncertainty, or insufficient persistence prevents confirmation; a reconstructible cycle is not required |
| INSUFFICIENT_OBSERVABILITY | Known unsupported configuration, absent instrumentation, or invalid identity contract makes the predicate unevaluable |

Both confirmed verdicts require complete evidence for the declared scope in v2's first
implementation. Unknown counterparts, unseen slots and expired evidence never count
as progress. Report the exact missing items. The two confirmed labels are incomparable;
this is an information ordering, not a claimed four-element mathematical lattice.

Every report includes schema version, immutable manifest identity, evaluation context,
query scope/cut, certified horizon, persistence interval when applicable, evidence set,
coverage and missing-evidence reasons. Detection confidence is not remediation safety.

## 8. Correctness obligations

- S1: under A1–A6, a positive verdict witnesses the stated predicate for its reported
  evidence set and guaranteed interval at a causally consistent cut.
- S2: under A1–A6, a negative verdict refutes the closed-wait predicate for the complete
  declared query scope at its cut. It does not rule out a different historical cut.
- L1: a qualifying wait that remains present through collection and evaluation is
  eventually reported if observations/checkpoints are delivered, clocks remain
  qualified, and the analyzer is scheduled fairly. A finite implementation bound must
  include scan, checkpoint, delivery, processing and evaluation delays (design §5.7).
- Reduction terminates, is independent of worklist order, and never uses unknown state
  to discharge a member. Replay is deterministic for identical manifest, observation
  envelopes, evaluation times and configuration.

These remain obligations until proved. Use “designed to satisfy under A1–A6,” not a
soundness claim. Bounded model checking and zero observed errors do not constitute proof.

## 9. Required deliverables and acceptance

| Deliverable | Acceptance evidence |
|---|---|
| Real gRPC testbed and independent truth recorder | Deterministic trigger and truth naming exact jobs/slots without reading SpanLease events |
| Admission and transport instrumentation | Queued job visible before execution; reconstructed occupancy matches actual gate state; caller/server joins complete |
| Complete-prefix telemetry protocol | Startup/idle checkpoints, loss/overflow/duplicates/restart and response-order tests |
| Analyzer | S1/S2 argument, reducer oracle checks, conservative verdicts and deterministic replay |
| Litmus integration | Deterministic closed wait → exact expected evidence set; fully observed spare-capacity cyclic pattern → CONFIRMED_NO_DEADLOCK |
| Baselines | Metrics, completed traces, ordinary and equally enriched live spans, invocation monitor, documented DDMon adaptation, triggered buffering where feasible |
| Evaluation | k/length/replica/load/deadline sweeps; adversarial negatives; at least one independently sourced supported workload; overhead and confidence intervals |
| Reproduction and manuscript | Pinned build, seeds, raw observations/truth, scripts, proof status and source-backed claims |

No remediation, broad async support, invented missing observations, production-wide
absence claims, or new framework is part of this scope. `tasks.md` makes the approval,
implementation, proof and publication dependencies explicit.
