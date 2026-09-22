# B-FEAS Runtime Semantics Handoff

Status: test-only implementation evidence ready for Dev A/Dev C review; B-FEAS is not
accepted and G0 is not approved. Owner: Dev B (Jobin). Source tested: `c5ab975` on
`jobin-b-feas-impl`, based on `Jobin` commit `0b40464`.

## Environment and commands

macOS arm64; checksum-verified Azul Zulu Java 21.0.12.1, Gradle 8.12.1, pinned
grpc-java 1.68.1 and OTel SDK 1.45.0. The temporary JDK archive SHA-256 was
`042093e0895c940a02d68e727bc37b59f3958e58aa1463ec9080845d77af0a45`.
The JDK and Gradle logs are local ignored execution artifacts, not committed results.

With `JAVA_HOME` and `PATH` selecting that JDK, these commands passed:

```bash
./gradlew --no-daemon clean build
./gradlew --no-daemon :analyzer:test :testbed:test
./gradlew :testbed:test --tests '*feasibility*' --rerun-tasks
git diff --check
```

The final clean build executed 39 Gradle tasks and 29 JUnit test cases with zero failures,
errors or skips; 23 cases are in the feasibility package. The final 23-case feasibility
selection passed on three consecutive forced runs.
The separate analyzer/testbed request was
up-to-date after the clean build, not a second fresh test execution. `integrationTest`
does not exist yet; its real detector litmus suite belongs to I-CI after G2.

## Test-only architecture

All Java is under `testbed/src/test/java/dev/spanlease/testbed/feasibility/`. A real
loopback Netty server uses `ServerStreamTracer.Factory` to copy metadata at transport
arrival, a generated unary adapter for decoded READY, a dedicated gRPC callback
executor, and a bounded FIFO gate with named single-thread application slots. The
generated `ChainGrpc` blocking stub runs on a separate test caller executor. The
client interceptor records SENT and attaches metadata before `ClientCall.start`; a
server close wrapper reserves one response reference before delegating close; the
client listener reads terminal trailers before delegating `onClose`. A test-only OTel
span processor records synchronous application span start/end after acquire. The
ordered journal is in-process test evidence, not versioned detector telemetry,
independent truth, a production sequence stream, or analyzer input.

## Observed hook lifetimes

The tests assert these partial orders for one loopback-process journal:

```text
caller: BLOCK_BEGIN < SENT < ARRIVAL < READY
saturated job: READY < QUEUED < ACQUIRE < APPLICATION_SPAN_START
immediate job: READY < ACQUIRE < APPLICATION_SPAN_START
response-held job: RESPONSE_RESERVED < RESPONSE_OBSERVED < BLOCK_END
                   < EXECUTION_END < RELEASE
running cancellation: APPLICATION_SPAN_START < CANCEL_REQUEST
                      < EXECUTION_END < RELEASE
```

An application span ends in the job's `finally` path before gate EXECUTION_END; a
cancelled caller's BLOCK_END can occur while that server job still owns a slot.
The test does not impose a cross-host order from sequence/time: the journal is a
single-process probe, and production causal order will require exact references.
`BLOCK_BEGIN`/`BLOCK_END` delimit the helper's logical generated-stub call, not
physical JVM parking. A server response is terminal for the RPC, not for its handler
job or slot ownership.

| Boundary | Hook and available input | Thread/lifetime evidenced |
|---|---|---|
| Caller invocation | Helper `BLOCK_BEGIN`; interceptor `SENT` writes ID and sent reference before `ClientCall.start` | Dedicated `bfeas-caller-*`; interval ends at helper `BLOCK_END` exactly once |
| Transport arrival | `ServerStreamTracer.Factory` copies request headers into immutable Context identity | Before decoded READY; the transport hook's particular thread is not asserted |
| Decoded admission | Unary adapter validates identity/head hop and records READY; gate records QUEUED or ACQUIRE | Callback executor is configured separately; admitted behavior and application span run on `bfeas-app-*` |
| Terminal response | First `ServerCall.close` reserves response reference before delegating; listener copies trailers before delegating `onClose` | The helper can return while the application job still holds the slot; exact callback thread at response close is not asserted |
| Cancellation | `ServerCallStreamObserver.setOnCancelHandler` calls gate cancellation | Asserted `bfeas-grpc-callback-*`, distinct from `bfeas-app-*`; it does not release a running slot |
| Actual exit | Handler and span `finally` complete; gate records EXECUTION_END then RELEASE | Named application slot executor; release is after actual job return |

## Queue/admission evidence

With one slot held and one waiting position, a second decoded RPC records ARRIVAL,
then READY, then QUEUED with no acquire or application span. A third returns
`RESOURCE_EXHAUSTED` and records REJECTED without slot ownership. Releasing the first
job assigns the second before its application span begins. A queued cancellation
removes its job without acquire/span; a running cancellation sets a token without
releasing its slot. A throwing gate job records EXECUTION_END then RELEASE, and the
slot runs a subsequent job. Missing request identity returns `INVALID_ARGUMENT`
before READY/gate submission; the stream tracer records INVALID_ARRIVAL.

## Response-before-exit evidence

A handler sends `onNext`/`onCompleted` and then waits on a latch before returning.
The blocking caller finishes with RESPONSE and exactly the response reference
`server@svcA#<RESPONSE_RESERVED journal ordinal>` while the gate still owns its
slot and has no EXECUTION_END/RELEASE. Releasing the latch then records end before
release. The corresponding request arrival has exactly
`client@probe#<SENT journal ordinal>`; a duplicate `ServerCall.close` attempt in
the focused test reserves only one reference. The journal ordinal is a test label,
not the proposed v2 `sl.local_seq`.

## Cancellation and thread-separation evidence

The real-RPC cancellation callback recorded CANCEL_REQUEST on a thread named
`bfeas-grpc-callback-*` while the application handler stayed on
`bfeas-app-svcA-slot-*`. The handler's cooperative token became requested, but
ownership and the absence of EXECUTION_END/RELEASE persisted until a separate
cleanup latch was released. The helper classified LOCAL only because the test
explicitly called its `cancelLocally` hook. Cancellation notification by itself
did not free capacity. The test fixture uses bounded waits and no `Thread.sleep`
for synchronization.

A focused adapter regression simulates cancellation arriving immediately when the
handler is registered, before gate submission. The adapter rechecks cancellation
after admission so the assigned job's token is signalled. This regression uses a
controlled observer, not a second real-transport timing claim.

## Completion-origin classification

- A valid observed terminal response reference yields RESPONSE, even for an
  instrumented remote `INTERNAL` error.
- Explicit cancellation initiated by this helper yields LOCAL when no valid
  response reference was observed.
- A remote `INTERNAL` error from a deliberately uninstrumented endpoint with no
  response trailers yields UNKNOWN, never a fabricated LOCAL result.
- The helper records one BLOCK_BEGIN and one BLOCK_END per tested call. It does
  not infer LOCAL from missing trailers alone. A controlled unexpected stub dispatch
  exception also produces one terminal UNKNOWN result rather than escaping as an
  unclassified future failure.

## Verification results

Focused journal, gate, server-hook, client-hook and real-RPC integration tests all
passed, as did the 29-case clean repository build. Exact request and response
reference equality is asserted in the real-RPC tests. The checked boundaries
illustrate I2, I3, and I5 in this fixture only; they are not production invariant
proofs. No v2 DTO, event, manifest, report or fixture was added or migrated.

## Limitations and unresolved questions

- This is an isolated feasibility fixture, not production v2 instrumentation,
  a complete-prefix protocol, an exporter, a detector, or independent truth.
  It does not establish S1/S2/L1, novelty, a finite detection bound, or general
  behavior outside the supported blocking-unary deployment.
- Only helper-initiated cancellation is positively classified LOCAL. A deadline,
  transport failure, cancellation/response race, or error with missing/malformed
  trailers is not positively classified unless a separate hook proves its origin.
  The proposed v2 `rpc.block.end` completion contract permits only response/local;
  A/C must decide whether to narrow supported cases or revise the contract before
  B-CONTRACT/G0. UNKNOWN cannot be silently serialized as LOCAL.
- Counterexample for that decision: the observed remote `INTERNAL` without trailers
  and a local failure without trailers both lack a response parent. Absence of trailers
  cannot distinguish them; marking either LOCAL merely from absence loses causal
  evidence. Proposed contract amendment for review: represent `completion_kind=unknown`
  as an explicit invalid/incomplete-evidence outcome on block end, with a versioned
  fixture migration and analyzer coverage reason; alternatively restrict the
  supported environment to endpoints that always produce verified response trailers
  and demonstrate a separate positive local-failure hook. Do not adopt either without
  the contract-tagged A/B/C review.
- The test gate has bounded FIFO state, but no production I1 sequence/publication
  discipline, init/checkpoints, scanner, leases, nonblocking telemetry queue or
  network export. Consequently I4/complete-prefix and R1–R16 production races are
  not proved by this spike. Duplicate metadata-key ambiguity and malformed response
  metadata need contract-level validation tests in B-RPC.
- The test uses deterministic invocation IDs and in-process journal ordinals; it
  does not implement production uniqueness or cross-host clocks. Callback execution
  and cleanup behavior was observed on this pinned stack and local environment only.

## A/C acceptance checklist

- Dev A: review gate/adapter semantics against A-SPEC, particularly assignment,
  cancellation, response-before-exit and bare/instrumented equivalence requirements.
- Dev C: review response-parent and UNKNOWN-origin implications against C-MODEL,
  complete-evidence treatment and the proposed v2 report/replay contract.
- All three: resolve the positive-local/deadline boundary explicitly before
  approving any contract-tagged v2 change. B-FEAS review acceptance, C-MODEL,
  B-CONTRACT, C-CONTRACT and R-LIT remain prerequisites for G0.
