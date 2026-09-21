# A-SPEC — Scenario lifecycle and independent ground truth

Revision: 2026-09-21. Owner: Dev A. Consumers: B-FEAS, C-MODEL,
C-CONTRACT, A-TESTBED and A-TRUTH.

Status: authored for review; **Dev B lifecycle acceptance is recorded in section 8;
Dev C truth/clock/oracle acceptance remains pending**. This is a behavioral handoff,
not a production Java API or a replacement for the pending G0 event/report contract.
Requirements and design remain authoritative. No scenario runner, recorder or deadlock
detector is implemented by this document.

## 1. Supported run and inputs

Each finite run fixes its seed, source commit, mode (bare/instrumented), static
service-to-instance bindings, process epochs, named pools/slots, queue capacities,
finite hop lists, root arrival schedule, deadlines, clock qualification and optional
fault schedule. Use design §4.1 defaults unless an explicit override is recorded.
The manifest contains configuration, never ownership, expected labels or truth.
The driver is included in the observed deployment in instrumented runs.

Only generated blocking unary client stubs are supported. Each handler issues at
most one downstream call at a time while retaining its slot. Disable transparent
retries and hedging on every channel; use no application retries in the initial
scenarios. A later sequential retry is a distinct attempt with a new identity.
Resolve a call to one instance; another replica's free slot cannot satisfy it.
Transport/callback execution must be independent of bounded application workers.

The existing `Chain.Call` proto is unchanged. `remaining[0]` names the receiver;
that receiver consumes the head, performs `work_ms`, calls the next hop with the
tail if any, performs `post_hold_ms`, then replies. Empty or mismatched input is
rejected before application admission. The successful leaf counts one completed
hop; each successful ancestor adds one. Failures propagate without inventing a
successful hop count. Validate unsigned wire durations before converting to Java
signed values or scheduling work. The finite hop list prevents accidental recursion.
An overall root deadline is propagated as a remaining budget, not reset to 30 s at
each hop. Record the earliest relevant deadline and actual cancellation times.

`post_hold_ms` is before reply, per the frozen proto. Response-before-exit is a
separate test lifecycle hook after terminal response close, not a reinterpretation
of this field or a new proto field.

## 2. Lifecycle and linearization boundaries

| Boundary | Required application/truth meaning |
|---|---|
| Transport receipt | Headers reach the early stream hook; a decoded job need not exist yet |
| Ready | Unary request decoded and validated; target pool and job fixed |
| Queue | Ready job waits for any eligible slot; no execution/application span exists |
| Assign | Gate atomically removes queue entry if present and reserves one named slot |
| Run | Assigned job begins application work, strictly after assignment |
| Call enter | Job enters one logical downstream blocking-call interval |
| Call return | That helper returns/throws exactly once; record result and completion origin if known |
| Response close | Terminal response is handed to gRPC; delivery is not assumed |
| Cancel request | Cancellation/rejection requested; neither job exit nor slot release |
| Actual exit | Application body and cleanup finish, even after response/cancellation |
| Release | Gate returns the slot after actual exit; next assignment may now occur |

Readiness is not inferred from transport receipt. Queue-versus-assignment and
queued-cancel-versus-assignment are atomic gate decisions. If cancellation wins
before assignment, the job never owns a slot. If assignment wins, cancellation
does not erase it. A running cancelled job owns its slot until actual exit.
Admission overflow rejects promptly without taking a slot. Exceptional paths must
still exit and release; do not conflate a remote response with local cleanup.

An assigned-but-not-yet-running job owns capacity. A nonblocked owner is only a
progress witness within the supported model; a hidden barrier/lock can invalidate
that interpretation. Test barriers are therefore prohibited inside measured
closed-wait intervals. Short state locks are allowed; network calls and application
callbacks must not execute while holding the gate state lock.

## 3. Truth identity and recording contract

Truth is a separate recorder of actual gate decisions and application call/return
boundaries. It must work when SpanLease is disabled and must not read event objects,
`local_seq`, leases, checkpoints, reconstructed graphs or analyzer helpers.
Sharing the application's transition boundary is necessary for fidelity; sharing
the detector's state reconstruction or emitted records is not independent truth.

Logical truth record fields (A-TRUTH chooses Java types/storage in its task):

| Field | Meaning |
|---|---|
| run / process epoch | Truth run identity and independently allocated process lifetime |
| truth ordinal | Contiguous per-process recorder counter, unrelated to detector sequence |
| transition | One of the application boundaries in §2 or initial/final physical-state snapshot |
| job | Independent job identity allocated when a decoded request becomes ready |
| attempt | Workload root ID + hop index + attempt index; shared across endpoints via workload context |
| parent attempt | Exact caller attempt, absent for a root; not inferred from timestamp proximity |
| pool / slot | Physical gate identity and stable slot index, absent before assignment |
| local monotonic time | Boundary timestamp in the owning process only |
| reference-time bounds | Earliest/latest possible reference time including measured recorder delay |
| outcome / cause | Actual success/error, cancellation source, rejection or failure; unknown stays unknown |

For the initial finite, retry-free chain, the root `request_id` and configured chain
length minus remaining length uniquely determine hop index. Do not assume this
works for branching, repeated root IDs or retries; those require an explicitly
reviewed workload identity extension. Job IDs and counters are independent of
SpanLease invocation/execution IDs even when their underlying calls correspond.

At assignment, record the actual worker/slot association atomically with the gate
decision. Record entry/exit independently around application work and blocking
calls; reconcile physical final occupancy and completed futures with the journal.
Transport receipt without ready may use the truth attempt identity without a job.
A crashed process may have no terminal snapshot: record truncation, not fictitious
release. Recorder overflow, missing records or failed clock qualification makes
the affected truth interval **unscorable**, not a negative label.

Buffer truth independently; no synchronous disk/network I/O in worker transitions.
Finite capacity and overflow status are explicit. Export after the run or on a
separate recorder thread. Truth recorder overhead is held constant across modes
and measured; its own perturbation remains a validity caveat.

The evaluator alone stores a mapping of truth attempt/job/physical slot to detector
invocation/execution/unit identities. Capture associations at the integration
boundary without using detector IDs as truth primary keys. Missing/ambiguous
mapping invalidates localization scoring; never guess from nearest timestamps.
Mapping files, journals, expected outcomes and trigger state never enter the
analyzer. The analyzer gets only allowed manifest, observations and query context.

## 4. Clock alignment, onset and scoring

Use local monotonic order/durations within a process. Never compare raw monotonic
timestamps across processes. Record a reference-clock calibration interval and
validity period per process, including drift allowance and recorder uncertainty.
The configured ε is a bound to qualify, not proof of synchronization. Same-host
tests can use a shared reference source but still record its qualification.

For a required assertion whose start is in [l_i,u_i] and end is in [L_i,U_i],
the guaranteed common interval is [max(u_i), min(L_i)), if nonempty. Starts and
ends must describe the same uninterrupted ownership, wait and eligibility facts.
For a fixed closed set, onset lies in [max(l_i), max(u_i)]. A final independent
snapshot may bound an open assertion only with a complete intervening journal;
missing terminal records cannot extend truth. Cancellation ends the qualifying
uncancelled wait even if physical occupancy continues.

For example, starts [100,110], [120,130], [115,125] imply onset [120,130]. If the
first possible closing time is 2200, guaranteed overlap is [130,2200), 2070 ms.
With τ=2000 ms, that fixed set qualifies. An onset interval is not itself a duration.

If verdict availability at the evaluator is [d_l,d_u] and onset is [o_l,o_u],
report latency [d_l-o_u, d_u-o_l]. Keep negative endpoints visible as timing or
alignment issues; do not clamp them into plausible measurements. Distinguish
analyzer evaluation time, certified historical interval and report receipt time.
Compare time saved with the earliest relevant deadline interval, not an assumed
fresh deadline at each hop. Cross-host intervals without qualified bounds cannot
support precise latency or persistence scoring.

For small reference cases, independently enumerate nonempty subsets of blocked
jobs and queued attempts and check the closure predicate directly against truth
snapshots. Do not reuse the production reduction worklist or its graph helpers.
For temporal checks require unchanged facts throughout the scored interval.
Compare the full maximal qualifying residual at the report's endpoint, including
upstream dependents, not a shortest cycle. If time uncertainty makes membership
ambiguous, record an unscorable/bounded comparison rather than a forced error.
C-MODEL reviews this oracle method; A-TRUTH implements physical recording.

## 5. Deterministic run phases

1. Validate configuration, unique workload IDs, supported routing and clock bounds.
2. Start separate transport/callback workers, gate, recorder and optional telemetry.
   Inventory all slots as free before admission; instrumented startup supplies init.
3. Warm up outside measurement; drain and verify no ownership remains. Start the
   measured history with explicit state evidence, not an assumed clean warmup.
   Retain startup and warmup observations for contiguous-prefix certification;
   do not reset sequence numbers or emit a second init in the same epoch. A fresh
   isolated measurement instead starts a new run/epoch before admission.
4. Admit the scenario roots. Where needed, use setup latches to ensure intended
   jobs hold all required slots before downstream calls proceed.
5. Release every setup latch. Record all releases and waiter departures. A call
   made after release cannot depend on another still-closed setup barrier in the
   scored interval. The last barrier departure is an eligibility lower bound.
6. Measure physical history for the configured finite window. Detector verdicts
   do not trigger release, cancellation or truth labels. Use independent scheduled
   stop conditions; record if a deadline intervenes before τ can be established.
7. End measurement, request cancellation, drain with bounded waits and shut down.
   If a worker does not terminate, fail cleanup; do not silently mark slots free.
8. Persist journals, losses, mappings, manifests, observations and report context
   separately. Evaluate afterward; retain failed/unscorable runs with reasons.

Latches/barriers and bounded awaits coordinate tests, never sleeps-for-sync.
Configured workload duration is application work, not a synchronization substitute.
Record actual scheduling delays; a seed does not make OS/network scheduling identical.

## 6. Required scenario cases and exact expectations

These are acceptance specifications, not assertions that runtime tests already pass.
Detector expectations below assume complete qualified v2 evidence and eligible leases;
otherwise requirements §7 mandates the appropriate inconclusive/unsupported verdict.

| Case | Setup and independent expectation |
|---|---|
| Serial k=1 closed wait | One root with finite hops A→B→C→A, one slot each. Jobs a0,b0,c0 own the slots and block on the next hop; final attempt a1 queues at A. Residual = {a0,b0,c0,a1(queue)}, units = {A0,B0,C0}. Final A would be a leaf if admitted. After ≥τ guaranteed overlap expect CONFIRMED_DEADLOCK |
| k>1 saturated ring | Start k roots at each of A/B/C, each with two hops to the next service. Hold their first jobs at setup latches until all 3k slots are occupied, then release all. All 3k leaf attempts queue at the next pool. Residual = all 3k first jobs + all 3k queued leaves; units = all 3k slots. One missing blocked owner invalidates this expected closure |
| Spare-capacity pattern | Same finite A→B→C→A chain but A has two slots, B/C one. Final A leaf can acquire spare A1 and finish; reduction is empty at a fully known progress cut. Expect CONFIRMED_NO_DEADLOCK, not a cycle label from service names |
| Saturation without circularity | Fill k slots with finite local work and queue extra roots; owners have no open downstream wait. Expected closed residual empty despite full utilization |
| Slow progressing chain | Long finite local work at the leaf; ancestors block. Leaf is a nonblocked progress witness, so no closed residual; deadlines must allow the intended sample |
| Upstream dependent | Add U→A after the k=1 cycle is established; U owns its slot and waits on another queued A attempt. The old cycle qualifies first; U and its queue member join the persistent residual only after their own ≥τ interval |
| Disjoint cycles | Two independently triggered rings with different onset intervals. Score every qualifying closed member at the selected endpoint; a newer ring cannot reset the older ring's persistence |
| Cancel queued / assigned / running | Force each ordering using latches. Queued winner has no assignment; assigned/running winner retains ownership until actual exit. Cancel-pending helper gives inconclusive eligibility, not an inferred free slot |
| Response before exit | Use a lifecycle hook after response close to delay cleanup in a diagnostic test. Caller may return while server slot remains occupied. This hook is a hidden dependency and is not used to claim an RPC/slot-only positive wait |
| Admission rejection | Exceed configured waiting capacity while workers are occupied; rejected job never owns a slot and returns an error. Verify workload failure separately from deadlock classification |
| Crash / observation loss | Preserve pre-crash truth, mark later journal unavailable. Dropped/reordered telemetry changes delivery history only. Lost prefixes/checkpoints cannot be repaired by a truth file or lease expiry |
| Wrong replica / unsupported resource | Spare capacity elsewhere cannot discharge an instance-bound queue. Known uninstrumented gate/async/hidden-resource configuration is unsupported, not an in-scope negative |

For spare capacity, a fast leaf may finish before an intermediate query. Do not add a
hidden blocking latch simply to force the desired graph. Capture actual history and
use a qualified cut or bounded finite work. C-CONTRACT must supply complete evidence
fixtures for the specific queried state; topology alone is not test evidence.

Observation-fault schedules are separate from true-history schedules. Delay/loss/
duplication/reordering operates on copies of emitted observations. Injected telemetry
clock skew must not corrupt the independent reference clock. Real crash/cancellation
changes application history and is recorded as such. Outside-assumption runs are
labeled separately; arbitrary hidden violations need not be detectable.

## 7. Bare/instrumented equivalence and B-FEAS handoff

Both modes use identical routing, unary adapters, application gate capacity/queue
policy, deadlines, worker lifetime, error handling and truth recorder. The only mode
difference is detector hooks/export/scanning. Do not compare unbounded callback work
against bounded application work as an instrumentation overhead result.

At B-INTEGRATE converge on one gate implementation with instrumentation optional;
the initial A-TESTBED bare gate must obey the same semantics. Compare independent
transition invariants, outcomes and distributions, not identical thread schedules.
Include empty/overflow queues, exceptions, cancellation and response-before-exit.

B-FEAS receives this document and the A-BOOT build, not guessed gate interfaces.
Its prototype must provide a hook timeline and executable evidence for:

- Early stream identity versus decoded readiness; queued request before application span.
- One admitted job per slot, blocking downstream call on the application worker,
  with callbacks/cancellation still runnable independently.
- Cancellation request, helper return, response close, actual exit and release distinct.
- Successful response trailers joined before helper return; local failure versus
  missing remote metadata classified without guessing.
- Independent physical job/slot trace sufficient for A/C to audit those assertions.

B reports feasible hooks and proposed APIs; A/C review before B-CONTRACT/G0. No
production v2 type, replay migration or G0 approval is implied here.

## 8. Review and remaining caveats

Acceptance record: Dev A authoring complete. Dev B (Jobin) accepted the lifecycle,
gate, RPC and bare/instrumented-equivalence handoff on 2026-09-22 against source commit
`21c8fb4`; this acceptance unblocks B-FEAS only. Dev C truth/clock/oracle review remains
**pending**. Resolve disagreements in decisions.md before dependent implementation.

This specification cannot prove recorder correctness, qualified clocks, fair runtime
scheduling, feasibility of gRPC completion classification, or absence of hidden
dependencies. B-FEAS and later fidelity tests supply that evidence. Constructed
cycles are not a realistic workload evaluation. Truth overhead, finite deadlines,
cooperative cancellation, lost truth, ambiguous timing and mapping failures must
remain visible in reported results. Neither this artifact nor zero observed failures
establishes soundness, novelty, publication readiness or remediation safety.
