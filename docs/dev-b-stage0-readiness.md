# Dev B Stage 0 Readiness

Status: planning and review aid only. This document does not complete `B-FEAS`,
start `B-CONTRACT`, approve contract v2, or satisfy `G0`. The authoritative scope,
semantics, dependencies, and decisions remain in `requirements.md`, `design.md`,
`tasks.md`, and `docs/decisions.md`.

## 1. Current readiness

As of 2026-09-22, commit `21c8fb4` supplies the Gradle wrapper, Java module build,
compatibility tests, A-BOOT verification record, and authored A-SPEC. Dev B independently
verified and accepted A-BOOT and accepted the A-SPEC lifecycle handoff for B-FEAS.
Remote CI status has not been independently observed; Dev C's A-SPEC review remains
pending. No accepted `C-MODEL` or all-developer v2 sign-off is recorded.

| Dev B activity | Required inputs | Current status |
|---|---|---|
| Prepare B-FEAS questions and acceptance plan | Existing requirements/design | Ready; this document |
| Review A-BOOT and A-SPEC handoffs | Dev A commit `21c8fb4` | Complete for Dev B |
| Execute B-FEAS prototype | Accepted A-BOOT and A-SPEC | Ready |
| Execute B-CONTRACT | Accepted B-FEAS and C-MODEL | Blocked |
| Coordinate G0 | All dependencies listed in `tasks.md` | Blocked |
| Implement B-TYPES or later production tasks | Accepted G0 | Blocked |

No production DTO, gate, RPC, scanner, or exporter implementation should begin from
this document. Draft discussion must not be cited as contract approval.

## 2. Required cross-lane handoffs

### 2.1 Request to Dev A: A-BOOT

Dev B needs an accepted build handoff containing:

- a Java 21 Gradle wrapper and the modules defined in `design.md`;
- dependency versions only in `gradle/libs.versions.toml`;
- grpc-java/protobuf generation for the existing `Chain` proto;
- JUnit 5, AssertJ, jqwik, and google-java-format wiring;
- CI that executes real tests rather than bootstrap guards; and
- the exact verification commands and results used for acceptance.

Catalog entries are pins to validate, not evidence that the dependencies compile or
interoperate.

### 2.2 Request to Dev A: A-SPEC

Dev B needs a reviewed scenario/truth specification that defines:

- the supported unary request lifecycle from driver send through actual handler exit;
- independent truth identities and evaluator-only mapping to observation identities;
- clock alignment and deadlock-onset uncertainty;
- the bare and instrumented gates' required semantic equivalence;
- cancellation, rejection, deadline, and response-before-exit scenarios; and
- the rule that every synchronization barrier is released before the measured wait.

The truth recorder must not consume SpanLease events, local sequences, or analyzer
helpers.

### 2.3 Request to Dev C: C-MODEL

Before B-CONTRACT, Dev B needs the accepted model handoff covering:

- whole-manifest scope and complete-prefix requirements;
- the closed-wait predicate and UNKNOWN-state treatment;
- continuous persistence and temporal-pruning counterexamples;
- the distinction between an evidence set and an irreducible/minimum cause;
- v1 counterexamples that the v2 contract must prevent; and
- assumptions required by the independent oracle and S1/S2/L1 arguments.

Any material disagreement between that model and `design.md` requires a concrete
counterexample and proposed contract change, not an implementation guess.

## 3. B-FEAS question

After A-BOOT and A-SPEC are accepted, B-FEAS must answer this bounded feasibility
question using the pinned grpc-java version:

> Can the supported blocking-unary deployment capture transport arrival before
> application admission, execute exactly one admitted handler job per named worker
> slot, preserve request and response causal identity, and distinguish cancellation,
> helper completion, task exit, and slot release without running blocking application
> work on gRPC callback threads?

The prototype is evidence about hook behavior. It is not the production event
contract, analyzer, exporter, or proof.

## 4. Proposed supported hook lifetime

This timeline restates the current design for experimental validation. Observed
grpc-java behavior wins only after review; any mismatch must be reported rather than
silently changing the contract.

1. The client blocking helper allocates a fresh invocation ID and opens the caller's
   logical block before invoking the generated blocking stub.
2. The client metadata hook serializes `rpc.invocation.sent` before transport dispatch
   and attaches the invocation ID plus the exact sent-event reference.
3. `ServerStreamTracer.Factory` receives request headers early, copies the required
   identity fields, and records arrival before application admission.
4. The unary adapter receives the decoded request, obtains the copied context, submits
   one application job to the explicit gate, and returns promptly.
5. If a named slot is free, the gate records acquire before scheduling application
   execution. Otherwise it records a queue wait. Queue overflow rejects without
   allocating an execution or slot.
6. The application job starts only after acquire. It retains the slot during nested
   blocking calls, response handling, cleanup, cancellation processing, and exceptions.
7. A server response-close wrapper reserves and records `rpc.response.sent`, inserts
   its exact reference in trailers, and then delegates transport close once.
8. The client captures observed response trailers before the blocking helper returns.
   The helper emits exactly one block end, classified as response, positively known
   local completion, or invalid/unknown evidence. Missing trailers alone do not prove
   local completion.
9. A cancellation callback records a cancellation request and requests cooperative
   cancellation. It does not end the handler or release the slot.
10. The application job's `finally` path records actual execution end and then resource
    release under the gate's state discipline.

The feasibility report must name the grpc-java hook or wrapper responsible for every
step, the thread on which it runs, when its input becomes available, and when its
lifetime ends.

## 5. Feasibility scenarios and evidence

Use latches or barriers for synchronization; do not use sleeps as correctness
coordination. Timing measurements may record elapsed durations after deterministic
state transitions are established.

| Scenario | Required observation | Failure meaning |
|---|---|---|
| Immediate admission | Acquire is committed before application code starts | I2 cannot be supported by the proposed gate |
| Saturated gate | A decoded ready job is visible as queued before any application span/execution exists | Pre-handler queue localization is not feasible as designed |
| Queue overflow | Rejection produces no execution/slot ownership and returns promptly | Capacity or lifecycle semantics are ambiguous |
| Nested blocking call | One logical block surrounds generated blocking-stub dispatch and return | Proposed block boundary cannot be implemented reliably |
| Successful response | Client observes the exact server response reference before helper return | Response causal closure is unavailable |
| Response before exit | Response is sent/received while the server job still owns its slot; end then release follow later | Response and resource lifetime are being conflated |
| Deadline versus response | Exactly one terminal block end is produced with an attributable completion kind | R2 cannot be resolved conservatively |
| Queued cancel versus assignment | Cancel-before-assignment removes the queued job; assignment-win permits acquire then cancel | R3 ordering cannot be represented |
| Running cancellation | Cancel is observed without slot release until actual job exit | I3/R16 are violated |
| Exceptional handler exit | End then release occur and the slot is reusable | Exceptional paths can leak or fabricate capacity |
| Thread separation | Blocking application work runs only on gate workers; callback threads remain able to deliver cancellation/close | Supported deployment would risk callback-pool starvation |
| Malformed/duplicate metadata | Invalid identity is rejected or marked invalid; it is never silently aliased | A2/R14 cannot be enforced |

For each scenario, retain the seed/configuration, ordered hook trace, thread names or
roles, invocation/execution/slot identities, terminal outcome, and assertions. These
are feasibility artifacts, not independent truth or analyzer input.

## 6. B-FEAS acceptance checklist

B-FEAS is ready for A/C review only when all of the following are evidenced:

- [ ] The prototype uses the build and scenario contract accepted from A.
- [ ] The callback executor and monitored application gate are distinct.
- [ ] One admitted handler job maps to one execution and one named slot.
- [ ] Arrival is captured before admission without treating arrival as readiness.
- [ ] Saturated ready work is observable before an application span starts.
- [ ] Acquire occurs before application execution.
- [ ] Blocking boundaries describe the helper call, not a claimed JVM park.
- [ ] Request metadata refers to a committed sent event.
- [ ] Response trailers refer to a committed response-sent event.
- [ ] Response receipt/helper return, cancellation, task exit, and release remain
      distinct.
- [ ] Response-before-exit and cancel-before-exit are demonstrated.
- [ ] Application exceptions cannot leak a worker slot.
- [ ] No application path waits for telemetry network I/O or queue space.
- [ ] No blocking application work runs on transport/callback threads.
- [ ] Unsupported completion origins are reported as invalid/unknown evidence.
- [ ] Exact hook lifetimes and limitations are handed to A and C.

This checklist supplements but does not replace the `B-FEAS` completion row in
`tasks.md`.

## 7. B-CONTRACT review checklist

This review can be prepared now, but B-CONTRACT cannot be completed before accepted
B-FEAS and C-MODEL handoffs.

### 7.1 Common envelope and identity

- [ ] Every record has schema version, event type, instance epoch, positive local
      sequence, causal parent (possibly empty), wall time, and monotonic time.
- [ ] Trace/span fields are correlation only, never evidence identities.
- [ ] Invocation, execution, resource, unit, and event-reference formats are
      unambiguous and length-bounded.
- [ ] Production invocation IDs are unique independently of deterministic test IDs.
- [ ] Local sequence and monotonic time never order different hosts.
- [ ] Manifest membership, epoch, ownership, and identity joins are validated.

### 7.2 Producer state machines

- [ ] Invocation transitions distinguish ARRIVED, READY, QUEUED/ASSIGNED, RUNNING,
      and ENDED.
- [ ] Arrival alone never creates a queue wait.
- [ ] Execution identity is allocated only on assignment.
- [ ] A running cancellation is an orthogonal flag, not an implicit exit.
- [ ] Unit ownership ends only at observed release after execution end.
- [ ] One outstanding downstream invocation/block is enforced per execution.
- [ ] Retries allocate new invocation IDs; transparent retry and hedging are disabled.

### 7.3 Thirteen event types

For each proposed type in `design.md` section 4, provide at least one valid example,
one invalid example, its producer transition, allowed causal parent, and closing or
superseding transition. In particular, review:

- `resource.init` before admission, exactly matching the manifest;
- idle `instance.checkpoint` records with `through_seq = local_seq - 1`;
- `resource.wait.begin` only after the request is decoded and ready;
- acquire before application execution;
- leases tied to the exact current acquire and block references;
- response-sent before transport close;
- response versus local block-end classification;
- pre-admission cancellation/rejection with an empty execution ID;
- execution end for all handler outcomes; and
- release strictly after actual execution end.

### 7.4 Ordering, loss, and publication

- [ ] Local state transition, sequence allocation, and publication attempt satisfy I1.
- [ ] Records are immutable before leaving the state lock.
- [ ] Publication is a nonblocking bounded offer in allocated order.
- [ ] Failed offers preserve sequence holes and increment diagnostic loss state.
- [ ] A later checkpoint exposes a missing suffix, including while idle.
- [ ] Checkpoints certify only received contiguous prefixes and never repair gaps.
- [ ] Lease expiry affects eligibility, not ownership or progress.

### 7.5 Compatibility and fixtures

- [ ] Existing v1 YAML is explicitly historical and cannot confirm a v2 verdict.
- [ ] C-CONTRACT supplies migrated v2 replay/report fixtures and evaluation context.
- [ ] JSON with Jackson is used unless G0 explicitly approves a YAML dependency.
- [ ] Any wire-visible change from the reviewed candidate bumps the schema version and
      migrates fixtures in the same contract change.
- [ ] No hidden control channel, independent truth, or ordinary span fills missing
      detector evidence.

## 8. G0 review package

Dev B coordinates G0 only after every dependency in `tasks.md` is accepted. The review
package should contain:

- A-BOOT verification evidence and A-SPEC;
- C-MODEL and the updated, source-backed R-LIT review;
- B-FEAS code, deterministic scenarios, results, hook timeline, and limitations;
- B-CONTRACT's producer state machine plus valid/invalid examples for all event types;
- C-CONTRACT's report/replay contract, missing-evidence vocabulary, evaluation
  envelopes, and v1 migration plan;
- a schema diff from historical v1 to the proposed frozen version;
- all new dependency and fixture decisions;
- an explicit list of unresolved issues, with no implicit approval by silence; and
- recorded A/B/C approval on the same reviewed revision.

G0 passes only when the contract-tagged review records all three approvals and the
append-only decision log records the resulting freeze or superseding changes. A review
meeting, documentation draft, or partial approval is not G0 completion.

## 9. Dev B literature-review responsibilities

Dev B's review is limited to deployment and telemetry distinctions relevant to the
instrumentation lane:

- ensure detector inputs are described as custom OTel-compatible telemetry, not
  standard semantic-convention data;
- separate application gate capacity from grpc-java callback/transport executors;
- distinguish logical helper intervals from physical JVM parking;
- avoid claims that ordinary live spans contain queue/slot facts;
- treat executor depth and saturation metrics as custom/JMX observations without
  execution-localization claims;
- do not describe OTLP/gRPC transport as lossless;
- preserve the distinction between retryable OTLP failure and populated partial
  success, which does not request retransmission;
- label any DDMon or Hindsight implementation as a port/adaptation and do not inherit
  proofs; and
- make no priority, soundness, or finite timing claim without the required review,
  proof, and measurements.

Any source or product behavior added to the literature review must be checked against
the primary paper or official version-specific documentation before it is asserted.

## 10. Next action

Execute B-FEAS as the next isolated feasibility task and request the C-MODEL handoff
from Dev C in parallel. B-FEAS must provide executable hook evidence rather than rely
on the bootstrap compatibility tests. After B-FEAS and C-MODEL are accepted, use the
observed hook lifetimes and model counterexamples to complete B-CONTRACT and prepare
the contract-tagged G0 review.
