# Stage-1 runtime review and continuation

Date: 2026-10-27. Reviewed baseline: `8430515`, including its predecessor
`f04934e`. Owner perspective: Dev C correctness, with Dev A/B handoff review.
This is a single-implementer review under D13, not independent acceptance.

Follow-up: D16 and [`native-arm-demo-verification.md`](native-arm-demo-verification.md)
resolve the native generator failure recorded below. The clean build, all 59 tests
and both live scenarios subsequently passed; the correctness/gate findings remain open.

## Recent implementation and gate status

`8430515` adds the reusable gRPC client/server adapter, instance recorder, scheduled
leases/checkpoints, OTLP receiver, live analyzer process, and an instrumented Chain
deployment/demo. These are substantial runtime additions beyond the older status in
`tasks.md`. Their existence does not complete the corresponding acceptance tasks.

G0's solo-role acceptance is recorded by D14 and `stage0-g0-review.md`. G1, G2 and
G3 remain pending. In particular:

| Task / gate | Remaining evidence seen in this review |
|---|---|
| A-TESTBED / B-INTEGRATE | The live deployment always constructs the instrumented gate, scanners and OTLP pipelines. Bare/instrumented equivalence is not yet demonstrated. |
| A-TRUTH / A-HARNESS | `HandlerJournal` explicitly records only handler boundaries. It lacks independent gate assignment/release truth, truth-to-observation identity mapping and the complete scorer required by A-SPEC. Run artifacts contain manifests/reports/journals, but do not yet archive received observation envelopes for deterministic replay. |
| B-INTEROP | Receiver tests cover batch admission, malformed input and overload status. SDK retry/partial-success/message-size behavior and fidelity acceptance still need their stated evidence. |
| C-REPLAY / C-CUT | DTO/demo fixtures exist, but production history/identity/causal validation and complete replay acceptance remain incomplete. See the concrete review cases below. |
| C-TIME / C-PROOF | The production implementation still qualifies one entire residual at one endpoint. It lacks the specified temporal closed-subset/endpoint search and the reviewed production S1/S2/L1 argument. The separate test-only model is not evidence that production implements those algorithms. |
| I-LITMUS / I-FAULT / I-CI | `:testbed:integrationTest` exists but reports `NO-SOURCE`; no real litmus test source or CI invocation is present. G1 must be accepted before this lane proceeds. |
| G2 / evaluation | No G2 acceptance or baseline freeze is recorded. `baselines` has no implementation, and there is no `eval` workspace. E-LIVE/E-RPC/E-ABLATE and the larger experimental matrix must not be marked started or accepted from the live demo. |

## Correctness changes in this continuation

The behavior is already required by design §§5.2–5.5. No event/report field,
schema version, dependency, producer invariant I1–I5 or historical fixture changes.

### Received suffix gaps cannot disappear behind an older checkpoint

Counterexample: receive A#1–A#8, with A#8 a fresh checkpoint, then receive checkpoint
A#10 while A#9 is missing. The previous analyzer selected A#8 and could return
either confirmed verdict without reporting the hole. Δ elapsing cannot supply A#9.

Certification now returns inconclusive for every observed missing range, including
ranges after the selected checkpoint. Reports use the existing `SEQUENCE_GAP` reason
and inclusive `frontierGaps` fields. Range detection visits received positions rather
than enumerating absent positions. A delayed record can fill a gap and allow later
checkpoint certification; a permanently missing record continues to block it.
Contiguous records beyond a checkpoint do not invent a missing tail.

`AnalyzerPrefixCertificationTest` supplies eight cases for R4/R5/R7/R12/R13:
both verdict paths, multiple/large ranges, startup loss, late repair, persistent loss
after Δ, duplicate checkpoint freshness and a contiguous uncheckpointed suffix.
Before the fix six failed; after the fix all eight passed.

### Lease eligibility precedes observed-progress reduction

Counterexample: a complete spare-capacity graph with an open blocked execution but
no matching eligible lease previously reduced to empty and returned
`CONFIRMED_NO_DEADLOCK`. Also, a positive ring could hide an unleased blocked execution
on a separate progressing branch because only surviving witness members were checked.

Every non-cancel-pending blocked execution in the declared scope now needs an eligible
current-block lease at the existing narrowed checkpoint endpoint before either
confirmation path. Ownership remains intact; missing/expired leases give
`EXPIRED_LEASE`, never observed progress. Cancel-pending handling remains distinct.
The endpoint search required for C-TIME is still outstanding; this guard does not
claim to implement that search.

`AnalyzerLeaseCoverageTest` supplies five R7 cases: missing/expired leases with and
without spare capacity, plus the independent progressing branch. Before the fix the
two negative cases and the progressing-branch positive case failed; afterward all five
passed. Existing cancellation and epsilon-boundary tests also passed.

## Further concrete correctness review cases

These are source-review findings to turn into targeted counterexample tests and fixes;
they are not completed proof obligations or newly accepted contract choices.

1. **Causal parent validation (R10/R14):** `causalCut` checks whether the referenced
   sequence is within the vector, but not whether it has the required payload,
   invocation or endpoint identity. For example, replacing the request parent on
   `arrived(inv-ab)` with a received `resource.init` reference must invalidate the
   join; sequence inclusion alone must not certify it. Response parents need the
   analogous `rpc.response.sent`/invocation checks, plus causal-cycle validation.
2. **Transition validation (R6/R14/R16):** `apply` does not fully enforce instance-local
   ownership, unique identities or legal end/release transitions. For example, a
   release naming the current owner but with no preceding actual execution end
   currently clears the unit. Build invalid-history tests independently from producer
   happy paths before accepting C-REPLAY/C-INGEST.
3. **Temporal subsets (C-TIME):** add a new upstream blocked job just before evaluation
   of an old persistent ring. Production currently takes the maximum start across
   the entire residual and can suppress the old ring. The specified temporally closed
   subset should retain the qualifying ring and omit the young dependent. It must
   never turn this temporal pruning into a negative verdict.
4. **Production oracle linkage (C-PROOF):** the exhaustive truth oracle is test-only;
   its current tests exercise the oracle itself. Compare production results against
   independently enumerated, valid small histories before treating oracle checks as
   production reducer evidence. S1/S2/L1 still require their separate argument.

Continue these ready correctness/handoff prerequisites before G1, then implement and
accept the actual integration litmus/fault suite before G2 and experiments. Do not
infer approval from this review or from a successful demo.

## Verification and local toolchain limitation

Selected JDK: `/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home`.
Gradle wrapper: 8.12.1. Commands below use that path as `JAVA_HOME`.

| Command | Observed result |
|---|---|
| `./gradlew --no-daemon build :analyzer:test :testbed:test :testbed:integrationTest` before edits | Successful, but existing tests/classes were up to date; integrationTest was `NO-SOURCE`. This was not a fresh test run. |
| `./gradlew --no-daemon :analyzer:test --tests dev.spanlease.analyzer.AnalyzerPrefixCertificationTest` before gap fix | Eight executed, six failed for the missing gap guard/diagnostics. |
| `./gradlew --no-daemon :analyzer:test --tests dev.spanlease.analyzer.AnalyzerLeaseCoverageTest` before lease fix | Five executed, three failed for the missing whole-scope guard. |
| `./gradlew --no-daemon :analyzer:spotlessApply :analyzer:test` after both fixes | Successful; full analyzer tests executed, including all 13 new regressions. |
| `./gradlew --no-daemon clean build :analyzer:test :testbed:test :testbed:liveDemo` | Failed at `:testbed:generateProto`. Fresh analyzer/common/instrumentation tests passed before that failure. Testbed tests and liveDemo did not execute. |

The pinned `protoc-gen-grpc-java:1.68.1:osx-aarch_64` cached executable is reported by
`file` as **Mach-O x86_64**; direct execution returns **bad CPU type in executable**.
The pinned protobuf `protoc:4.28.3:osx-aarch_64` is arm64. This host cannot execute the
Intel gRPC plugin. The Gradle/protoc error misleadingly says the plugin is not found
or executable even though its executable bit is set.

Fresh full-build and real-runtime verification need a supported execution environment
for the pinned generator, or a separately reviewed catalog/toolchain change. No system
runtime was installed and no dependency pin was changed in this continuation. Existing
build artifacts had hidden this clean-generation failure; cached-build success must not
be cited as clean-environment reproduction.

This document supplies no experimental overhead comparisons, benchmark results,
soundness proof, timing guarantee, independent review or G1–G3 approval.
