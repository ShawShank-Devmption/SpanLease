# Dev B v2 Event and Producer-State Draft

Status: non-normative contract prework. This document does not start or complete
`B-CONTRACT`, approve schema version 2, migrate v1 fixtures, or satisfy `G0`. It
organizes the current proposal in `design.md` section 4 for review after `B-FEAS` and
`C-MODEL` are accepted. If this draft conflicts with `requirements.md`, `design.md`,
`tasks.md`, or `docs/decisions.md`, those documents govern.

## 1. Purpose and use

This draft gives Dev A and Dev C a concrete producer-side review surface:

- producer state machines and the transitions that allocate events;
- one illustrative valid JSON record for each of the thirteen proposed event types;
- a corresponding invalid case and the validation layer that should reject it; and
- questions that must be resolved by feasibility evidence or model review rather than
  guessed during implementation.

The JSON records are type-level examples, not a complete replay. Their references are
illustrative and assume the named earlier event exists in the same run. A future
contract PR must supply complete ordered streams, observation envelopes, evaluation
context, and invalid-history fixtures.

## 2. Producer-wide rules

Every proposed record uses the `sl.` attribute namespace and contains these common
attributes:

| Attribute | Producer rule |
|---|---|
| `sl.schema_version` | Integer `2`; v1 is never reinterpreted as v2. |
| `sl.event_type` | Exactly one of the thirteen names in section 4. |
| `sl.instance_id` | The emitting process epoch declared in the manifest. |
| `sl.local_seq` | Positive and allocated once under the instance state lock. |
| `sl.causal_parent` | Exact cross-instance event reference when required; otherwise the empty string. |
| `sl.wall_time_ms` | Physical timestamp qualified by the run's recorded clock evidence. |
| `sl.monotonic_time_ns` | Process-local elapsed-time reading; never compared across hosts. |
| `sl.trace_id`, `sl.span_id` | Optional operator correlation represented here by strings; never evidence identities. |

The state transition, local-sequence allocation, immutable-record construction, and
bounded publication attempt are serialized under one short instance-local critical
section. Application code, gRPC send/close, serialization, network I/O, and waits for
telemetry capacity remain outside that section. A failed offer consumes its sequence
position and increments diagnostic loss accounting; it does not roll back the state
transition or reuse the sequence.

## 3. Producer state machines

### 3.1 Invocation admission

```text
ABSENT
  -> ARRIVED                    rpc.invocation.arrived
ARRIVED
  -> READY                      decoded request becomes admissible; no event by itself
READY
  -> QUEUED                     resource.wait.begin
  -> ASSIGNED                   resource.acquire
  -> REJECTED                   execution.cancel(execution_id="", reason=rejected)
QUEUED
  -> ASSIGNED                   resource.acquire
  -> CANCELLED                  execution.cancel(execution_id="")
ASSIGNED
  -> RUNNING                    application job begins after acquire; no new event required
RUNNING
  -> ENDED                      execution.end
```

Arrival does not imply READY, QUEUED, or progress. An execution ID exists only after
assignment. If cancellation and assignment race, either cancel-before-assignment or
acquire-then-cancel may be valid, but their serialized event order must match the state
winner.

### 3.2 Execution and slot ownership

```text
NO_EXECUTION
  -> ASSIGNED                   resource.acquire; unit FREE -> HELD(execution)
ASSIGNED
  -> RUNNING                    job begins only after acquire is committed
RUNNING
  -> BLOCKED                    rpc.block.begin
BLOCKED
  -> RUNNING                    rpc.block.end
RUNNING or BLOCKED
  -> CANCEL_REQUESTED           execution.cancel; orthogonal flag, ownership unchanged
RUNNING
  -> ENDED                      execution.end after actual job exit
ENDED
  -> RELEASED                   resource.release; unit HELD(execution) -> FREE
```

Cancellation never performs the ENDED or RELEASED transition. A response or helper
return can occur while the handler remains RUNNING and retains its unit. Exception
paths still allocate `execution.end` before `resource.release`.

### 3.3 Outbound blocking call

```text
IDLE
  -> BLOCK_OPEN                 rpc.block.begin(execution, invocation)
BLOCK_OPEN
  -> SENT                       rpc.invocation.sent for the same invocation
SENT
  -> RESPONSE_OBSERVED          response trailers identify rpc.response.sent
  -> LOCAL_COMPLETION           positively classified local deadline/cancel/transport failure
RESPONSE_OBSERVED
  -> IDLE                       rpc.block.end(completion_kind=response,
                                               causal_parent=response.sent ref)
LOCAL_COMPLETION
  -> IDLE                       rpc.block.end(completion_kind=local,
                                               causal_parent="")
```

Only one block may be open per execution. The helper emits exactly one terminal
`rpc.block.end` from its `finally` path. Missing response trailers alone do not prove
local completion, because a remote error can also omit metadata; unclassifiable origin
is invalid evidence rather than an invented causal parent.

### 3.4 Server response

```text
RESPONSE_OPEN
  -> RESPONSE_RESERVED          allocate rpc.response.sent and exact event reference
RESPONSE_RESERVED
  -> CLOSE_DELEGATED            insert reference into trailers, then delegate close once
CLOSE_DELEGATED
  -> TERMINAL                   later close attempts do not allocate another response event
```

Reservation precedes transport close to satisfy I5. A close failure after reservation
does not prove that the client received the response. Only client-observed metadata
creates the response-parent relation used by `rpc.block.end`.

### 3.5 Block lease and checkpoint scanner

```text
SCANNER_TICK
  -> snapshot each currently open block under the state lock
  -> execution.lease only when age/renewal eligibility holds
  -> no lease when the block reference no longer matches the current open block

CHECKPOINT_TICK
  -> snapshot sequence/loss state under the state lock
  -> instance.checkpoint with through_seq = checkpoint local_seq - 1
```

Leases name the exact acquire and current block-begin references. Expiry affects
evidence eligibility, not ownership. Checkpoints continue while the instance is idle
so a received contiguous prefix can expose a missing suffix.

## 4. Proposed valid record examples

The examples use `svc-a@epoch-a` and `svc-b@epoch-b` as manifest instance epochs,
`main` as the fixed application pool, and UUID-shaped invocation IDs. Empty strings are
intentional only where the proposed contract permits them.

### 4.1 `resource.init`

Producer transition: initialize the declared pool as FREE before admission opens.
Closing/change transition: individual units change through `resource.acquire` and
`resource.release`; another init for the same pool in the epoch is invalid.

```json
{
  "sl.schema_version": 2,
  "sl.event_type": "resource.init",
  "sl.instance_id": "svc-a@epoch-a",
  "sl.local_seq": 1,
  "sl.causal_parent": "",
  "sl.wall_time_ms": 1000,
  "sl.monotonic_time_ns": 1000000,
  "sl.trace_id": "",
  "sl.span_id": "",
  "sl.resource_id": "main",
  "sl.capacity": 2,
  "sl.unit_ids": [
    "svc-a@epoch-a:main:slot-0",
    "svc-a@epoch-a:main:slot-1"
  ],
  "sl.initial_state": "free"
}
```

### 4.2 `instance.checkpoint`

Producer transition: serialize a checkpoint boundary after all earlier transition and
offer attempts. Closing/change transition: a later advancing checkpoint supersedes its
freshness, but never repairs an earlier sequence gap.

```json
{
  "sl.schema_version": 2,
  "sl.event_type": "instance.checkpoint",
  "sl.instance_id": "svc-a@epoch-a",
  "sl.local_seq": 12,
  "sl.causal_parent": "",
  "sl.wall_time_ms": 1500,
  "sl.monotonic_time_ns": 501000000,
  "sl.trace_id": "",
  "sl.span_id": "",
  "sl.through_seq": 11,
  "sl.dropped_total": 0
}
```

### 4.3 `rpc.invocation.sent`

Producer transition: commit the outbound invocation before actual transport dispatch.
Closing/change transition: the invocation is later paired with an arrival and terminal
response/local outcome; retries use a new invocation ID.

```json
{
  "sl.schema_version": 2,
  "sl.event_type": "rpc.invocation.sent",
  "sl.instance_id": "svc-a@epoch-a",
  "sl.local_seq": 4,
  "sl.causal_parent": "",
  "sl.wall_time_ms": 1100,
  "sl.monotonic_time_ns": 101000000,
  "sl.trace_id": "0123456789abcdef0123456789abcdef",
  "sl.span_id": "0123456789abcdef",
  "sl.invocation_id": "11111111-1111-4111-8111-111111111111",
  "sl.caller_execution_id": "svc-a@epoch-a:2",
  "sl.target_service": "svc-b"
}
```

### 4.4 `rpc.invocation.arrived`

Producer transition: the early server-stream hook copies and validates request identity
before application admission. Required causal parent: exact client sent-event reference.

```json
{
  "sl.schema_version": 2,
  "sl.event_type": "rpc.invocation.arrived",
  "sl.instance_id": "svc-b@epoch-b",
  "sl.local_seq": 2,
  "sl.causal_parent": "svc-a@epoch-a#4",
  "sl.wall_time_ms": 1102,
  "sl.monotonic_time_ns": 42000000,
  "sl.trace_id": "0123456789abcdef0123456789abcdef",
  "sl.span_id": "fedcba9876543210",
  "sl.invocation_id": "11111111-1111-4111-8111-111111111111"
}
```

### 4.5 `resource.wait.begin`

Producer transition: the decoded READY job finds no assignable unit and is queued
atomically with the event. Closing/change transition: acquire or queued cancellation.
Queue overflow rejects directly from READY and does not emit this event.

```json
{
  "sl.schema_version": 2,
  "sl.event_type": "resource.wait.begin",
  "sl.instance_id": "svc-b@epoch-b",
  "sl.local_seq": 3,
  "sl.causal_parent": "",
  "sl.wall_time_ms": 1104,
  "sl.monotonic_time_ns": 44000000,
  "sl.trace_id": "0123456789abcdef0123456789abcdef",
  "sl.span_id": "fedcba9876543210",
  "sl.invocation_id": "11111111-1111-4111-8111-111111111111",
  "sl.resource_id": "main",
  "sl.requested_semantics": "any_unit"
}
```

### 4.6 `resource.acquire`

Producer transition: assign the named FREE unit and allocate execution identity before
the job can run. Closing/change transition: `execution.end` followed by
`resource.release` for the same execution and unit.

```json
{
  "sl.schema_version": 2,
  "sl.event_type": "resource.acquire",
  "sl.instance_id": "svc-b@epoch-b",
  "sl.local_seq": 7,
  "sl.causal_parent": "",
  "sl.wall_time_ms": 1110,
  "sl.monotonic_time_ns": 50000000,
  "sl.trace_id": "0123456789abcdef0123456789abcdef",
  "sl.span_id": "fedcba9876543210",
  "sl.execution_id": "svc-b@epoch-b:7",
  "sl.resource_instance_id": "svc-b@epoch-b:main:slot-1",
  "sl.invocation_id": "11111111-1111-4111-8111-111111111111"
}
```

### 4.7 `rpc.block.begin`

Producer transition: the generated-stub helper opens one logical downstream call for
the execution. Closing transition: exactly one `rpc.block.end` for the same execution
and invocation.

```json
{
  "sl.schema_version": 2,
  "sl.event_type": "rpc.block.begin",
  "sl.instance_id": "svc-a@epoch-a",
  "sl.local_seq": 3,
  "sl.causal_parent": "",
  "sl.wall_time_ms": 1099,
  "sl.monotonic_time_ns": 100000000,
  "sl.trace_id": "0123456789abcdef0123456789abcdef",
  "sl.span_id": "0123456789abcdef",
  "sl.execution_id": "svc-a@epoch-a:2",
  "sl.invocation_id": "11111111-1111-4111-8111-111111111111"
}
```

### 4.8 `execution.lease`

Producer transition: the scanner atomically snapshots a still-current open block after
the age/renewal condition. Closing/change transition: block end or a different block
reference makes the lease inapplicable; expiry alone does not release ownership.

```json
{
  "sl.schema_version": 2,
  "sl.event_type": "execution.lease",
  "sl.instance_id": "svc-a@epoch-a",
  "sl.local_seq": 5,
  "sl.causal_parent": "",
  "sl.wall_time_ms": 1600,
  "sl.monotonic_time_ns": 601000000,
  "sl.trace_id": "0123456789abcdef0123456789abcdef",
  "sl.span_id": "0123456789abcdef",
  "sl.execution_id": "svc-a@epoch-a:2",
  "sl.invocation_id": "11111111-1111-4111-8111-111111111111",
  "sl.start_ref": "svc-a@epoch-a#2",
  "sl.block_ref": "svc-a@epoch-a#3",
  "sl.observed_age_ms": 501,
  "sl.lease_expiry_ms": 3100
}
```

### 4.9 `rpc.response.sent`

Producer transition: reserve the terminal server response and insert its event reference
into trailers before delegating close. Closing/change transition: terminal for that
server invocation; later close attempts allocate no second response event.

```json
{
  "sl.schema_version": 2,
  "sl.event_type": "rpc.response.sent",
  "sl.instance_id": "svc-b@epoch-b",
  "sl.local_seq": 10,
  "sl.causal_parent": "",
  "sl.wall_time_ms": 1700,
  "sl.monotonic_time_ns": 640000000,
  "sl.trace_id": "0123456789abcdef0123456789abcdef",
  "sl.span_id": "fedcba9876543210",
  "sl.invocation_id": "11111111-1111-4111-8111-111111111111",
  "sl.execution_id": "svc-b@epoch-b:7",
  "sl.outcome": "cancelled"
}
```

### 4.10 `rpc.block.end`

Producer transition: the client helper closes its current block exactly once. For a
response completion, the required causal parent is the exact observed
`rpc.response.sent` reference.

```json
{
  "sl.schema_version": 2,
  "sl.event_type": "rpc.block.end",
  "sl.instance_id": "svc-a@epoch-a",
  "sl.local_seq": 6,
  "sl.causal_parent": "svc-b@epoch-b#10",
  "sl.wall_time_ms": 1703,
  "sl.monotonic_time_ns": 704000000,
  "sl.trace_id": "0123456789abcdef0123456789abcdef",
  "sl.span_id": "0123456789abcdef",
  "sl.execution_id": "svc-a@epoch-a:2",
  "sl.invocation_id": "11111111-1111-4111-8111-111111111111",
  "sl.outcome": "cancelled",
  "sl.completion_kind": "response"
}
```

### 4.11 `execution.cancel`

Producer transition: observe a cancellation request or pre-admission rejection.
Closing/change transition: queued removal, block end, or actual job exit depending on
the state; the cancel event itself never releases a unit.

```json
{
  "sl.schema_version": 2,
  "sl.event_type": "execution.cancel",
  "sl.instance_id": "svc-b@epoch-b",
  "sl.local_seq": 9,
  "sl.causal_parent": "",
  "sl.wall_time_ms": 1695,
  "sl.monotonic_time_ns": 635000000,
  "sl.trace_id": "0123456789abcdef0123456789abcdef",
  "sl.span_id": "fedcba9876543210",
  "sl.execution_id": "svc-b@epoch-b:7",
  "sl.invocation_id": "11111111-1111-4111-8111-111111111111",
  "sl.cancel_source": "client",
  "sl.cancel_reason": "requested"
}
```

### 4.12 `execution.end`

Producer transition: the admitted handler job actually exits, including exceptional
and cancelled paths. Closing transition: `resource.release` for its currently held
unit.

```json
{
  "sl.schema_version": 2,
  "sl.event_type": "execution.end",
  "sl.instance_id": "svc-b@epoch-b",
  "sl.local_seq": 11,
  "sl.causal_parent": "",
  "sl.wall_time_ms": 1710,
  "sl.monotonic_time_ns": 650000000,
  "sl.trace_id": "0123456789abcdef0123456789abcdef",
  "sl.span_id": "fedcba9876543210",
  "sl.execution_id": "svc-b@epoch-b:7",
  "sl.invocation_id": "11111111-1111-4111-8111-111111111111",
  "sl.outcome": "cancelled"
}
```

### 4.13 `resource.release`

Producer transition: after actual execution end, return the unit held by the named
execution to FREE. Closing/change transition: a later acquire may assign the unit to a
new execution.

```json
{
  "sl.schema_version": 2,
  "sl.event_type": "resource.release",
  "sl.instance_id": "svc-b@epoch-b",
  "sl.local_seq": 12,
  "sl.causal_parent": "",
  "sl.wall_time_ms": 1711,
  "sl.monotonic_time_ns": 651000000,
  "sl.trace_id": "0123456789abcdef0123456789abcdef",
  "sl.span_id": "fedcba9876543210",
  "sl.execution_id": "svc-b@epoch-b:7",
  "sl.resource_instance_id": "svc-b@epoch-b:main:slot-1"
}
```

## 5. Invalid examples and rejection reasons

Each fragment is a mutation of its matching valid record. Contract validation covers
shape, enum, and identity format; producer-history validation additionally checks the
serialized state transition and referenced earlier event.

| Event type | Invalid fragment or history | Required rejection |
|---|---|---|
| `resource.init` | `{"sl.capacity":2,"sl.unit_ids":["svc-a@epoch-a:main:slot-0"]}` | Unit count does not equal declared capacity or manifest membership. |
| `instance.checkpoint` | `{"sl.local_seq":12,"sl.through_seq":12}` | `through_seq` must equal the checkpoint's own sequence minus one. |
| `rpc.invocation.sent` | `{"sl.invocation_id":"","sl.target_service":"svc-b"}` | Invocation identity is required and cannot be empty; a retry cannot reuse an earlier ID. |
| `rpc.invocation.arrived` | `{"sl.causal_parent":""}` | Arrival requires the exact request-sent reference copied from validated metadata. |
| `resource.wait.begin` | `{"sl.requested_semantics":"specific_unit"}` | The first implementation supports only `any_unit`; arrival-before-READY cannot enter this transition. |
| `resource.acquire` | `{"sl.local_seq":7,"sl.execution_id":"svc-b@epoch-b:8"}` | Execution ID must be derived from the same instance and acquire sequence; the unit must be FREE. |
| `rpc.block.begin` | A second begin for `svc-a@epoch-a:2` before its current block ends | One outstanding downstream invocation per execution; the second begin is an impossible transition. |
| `execution.lease` | `{"sl.block_ref":"svc-a@epoch-a#99"}` | Lease must name the exact current open block and its matching acquire/invocation. |
| `rpc.response.sent` | A second response-sent event for the same server invocation | Terminal response-close reservation is once per invocation, even if close is attempted again. |
| `rpc.block.end` | `{"sl.completion_kind":"response","sl.causal_parent":""}` | Response completion requires the exact observed response-sent parent; it cannot be inferred from time. |
| `execution.cancel` | Empty `sl.execution_id` after assignment has already won | Pre-assignment cancellation may be empty; after acquire, the current execution identity is required. |
| `execution.end` | End names an invocation different from the execution's admitted invocation | Execution/invocation ownership is immutable; aliasing invalidates affected evidence. |
| `resource.release` | Release occurs before `execution.end`, or names a unit held by another execution | I3 requires actual job end first, and only the observed owner may release the unit. |

Additional run-level invalidity includes unsupported schema versions, nonpositive or
reused local sequences, undeclared instance epochs/resources/units, malformed or
overlong identities, conflicting duplicate `(instance_id, local_seq)` payloads,
impossible state transitions, and causal references to the wrong event type or
invocation. These conditions must remain observable coverage defects; they are not
silently normalized into a usable history.

## 6. Producer review matrix

| Event type | Allocating producer | Required prior state/reference | Immediate producer state effect |
|---|---|---|---|
| `resource.init` | Gate initialization | Manifest pool; admission still closed | All declared units become initially observed FREE |
| `instance.checkpoint` | Checkpoint scanner | Serialized prior offers/transitions | Records prefix/loss boundary; no resource transition |
| `rpc.invocation.sent` | Client metadata/send hook | Fresh invocation; optional current caller execution | Commits invocation before dispatch |
| `rpc.invocation.arrived` | Early stream tracer | Valid sent reference from copied metadata | Creates ARRIVED invocation only |
| `resource.wait.begin` | Admission gate | Invocation READY; no unit assignable | Invocation becomes QUEUED |
| `resource.acquire` | Admission gate | READY/QUEUED invocation; selected unit FREE | Creates execution; unit HELD; invocation ASSIGNED |
| `rpc.block.begin` | Blocking helper | Execution RUNNING; no open block | Execution becomes BLOCKED on invocation |
| `execution.lease` | Lease scanner | Exact current acquire and open block | Refreshes evidence only; ownership unchanged |
| `rpc.response.sent` | Server response-close wrapper | Terminal response not already reserved | Reserves terminal response reference before close |
| `rpc.block.end` | Blocking helper | Exact current open block; classified completion | Closes block once; execution returns to nonblocked state |
| `execution.cancel` | Callback/gate/deadline observer | Invocation known; execution optional only before assignment | Sets cancellation/rejection state; never releases |
| `execution.end` | Application job finally path | Admitted execution has actually exited | Execution becomes ENDED; unit still HELD |
| `resource.release` | Gate finally/reassignment path | Same execution ENDED and owns named unit | Unit becomes FREE before any next assignment |

## 7. Review questions that must not be guessed

### 7.1 Requires B-FEAS evidence

1. Which pinned grpc-java hook provides the earliest stable request headers, and how is
   copied identity propagated to the decoded unary adapter without retaining mutable
   metadata?
2. On which executors and threads do stream tracing, service callbacks, cancellation,
   response close, and blocking-helper return occur?
3. Can response trailers be reliably captured for every supported success and remote
   error path before helper return, and which paths are positively classifiable as
   local completion?
4. Can the response event reference be reserved and placed in trailers before close
   without performing transport work under the instrumentation state lock?
5. What exact ordering is observable when queued cancellation races assignment, and
   when response delivery races deadline/cancellation?
6. Which malformed or duplicate metadata forms are delivered by grpc-java, and where
   can bounded validation reject them without aliasing identities?
7. What hook boundaries demonstrate that blocking application work never runs on the
   callback executor while cancellation and close delivery remain live?

### 7.2 Requires C-MODEL or C-CONTRACT review

1. Which producer-history violations invalidate the whole run versus only an affected
   scope, instance, invocation, or certified frontier?
2. Does every proposed causal parent type give the analyzer enough information to
   retract a cut without inventing cross-host order?
3. Are the producer transitions sufficient for the independent oracle to distinguish
   UNKNOWN, FREE, HELD, queued, blocked, cancelled, ended, and released states?
4. Which valid and invalid complete histories best expose the v1 completeness,
   response-causality, and persistence counterexamples?
5. Does the proposed event set make cancellation-pending and unclassifiable completion
   explicit enough to prevent either confirmed verdict?

### 7.3 Requires all-developer G0 review

1. Are field names, types, empty-string rules, enum values, ID length bounds, and
   manifest membership checks fully specified for deterministic DTO validation?
2. Are JSON fixtures the approved contract format, or is a reviewed YAML dependency
   and migration required?
3. Do B-FEAS findings require any wire-visible change from the proposed v2 table? Any
   such change must be versioned, fixture-migrated, decision-recorded, and reviewed by
   A, B, and C.

## 8. Completion boundary for this draft

This prework is useful when reviewers can trace every proposed event to a producer
transition and identify why each invalid case is unsafe. It intentionally does not
provide Java DTOs, OTel encoding, migrated replay fixtures, analyzer behavior, or a
contract freeze. After accepted B-FEAS and C-MODEL handoffs, Dev B must revise these
examples against observed hook behavior, add complete valid/invalid ordered histories,
and submit them through B-CONTRACT and the contract-tagged G0 review.
