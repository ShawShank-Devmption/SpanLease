# SpanLease — Three-Developer Execution Plan

Revision: 2026-09-19. Implements `requirements.md` and `design.md`.
Except for the artifact status below, tasks are **planned**, not completed. A review
document or a bootstrap CI pass is not detector implementation evidence.
Dependencies are finish-to-start unless a row
explicitly permits a draft handoff.

### Dev A artifact handoff — 2026-09-21

- **A-BOOT:** implemented and locally verified on Java 21 (`./gradlew clean build`);
  six test cases, including 100 seeded property checks, passed. Remote CI/team review
  remain pending. Evidence is recorded in `docs/bootstrap-verification.md`.
- **A-SPEC:** `docs/scenario-truth-spec.md` authored and accepted on 2026-09-22 by the
  sole implementer exercising the B lifecycle and C truth/clock review perspectives
  under D13. Independent review is not claimed.
- **B-FEAS:** real grpc-java lifecycle feasibility is verified by
  `GrpcGateFeasibilityTest`; its gate remains test-only and does not pre-empt B-GATE.
- **C-MODEL/R-LIT/B-CONTRACT/C-CONTRACT:** solo role review evidence and remaining
  limitations are recorded in `docs/stage0-g0-review.md`.
- **G0:** approved on 2026-09-22 under D13/D14 for solo schema-v2 implementation.
  Independent review is not claimed; G1–G3 remain pending.
- **Stage-1 implementation at `f04934e`:** B-TYPES and C-TYPES are implemented in
  `common`; B-GATE has ordered admission/exit, overflow and loss-gap tests; the blocking
  RPC lifecycle API, bounded OTel sink, OTLP decoder and finite-run analyzer core are
  implemented. B-RPC's reusable grpc adapter, scheduled B-SCAN, bounded OTLP receiver,
  independent A runtime/truth path and the complete R1–R16/G1 evidence remain pending.

### Runtime review and continuation — 2026-10-27

- Reviewed `8430515` (2026-10-26): reusable B-RPC adapters, scheduled B-SCAN,
  bounded OTLP receiver, live analyzer process and instrumented Chain demo now exist.
  This supersedes the preceding implementation inventory, not the acceptance gates.
- **C-CUT / C-REDUCE correctness fixes:** known sequence gaps after a fresh checkpoint
  now block both confirmations and report exact missing ranges. Whole-scope current-block
  lease eligibility is checked before reduction, including blocked executions outside a
  positive residual. Thirteen new R4/R5/R7/R12/R13 regression cases pass.
- **Fresh verification:** 41 analyzer, 5 common and 9 instrumentation tests passed,
  with no failures/errors/skips. A clean full build stopped at protobuf generation:
  the pinned macOS-arm gRPC generator is an Intel executable that this host cannot run.
  Testbed tests and live demos were not executed by that clean invocation.
- **G1–G3 remain pending.** Independent gate truth/mapping, bare-mode equivalence,
  complete transport/history validation, production temporal-subset search and proof
  review remain outstanding. `:testbed:integrationTest` currently has no sources;
  it is not I-LITMUS/I-CI acceptance evidence. Evaluation tasks still depend on G2.
- Detailed counterexamples, verification commands and next prerequisites:
  [`docs/stage1-correctness-review.md`](docs/stage1-correctness-review.md).

### ARM build and demo readiness — 2026-10-27

- Per the project owner's priority, finish native build/demo readiness before the
  full research backlog. D16 updates the shared grpc-java pin to 1.76.0, including
  its native ARM64 macOS generator. No Rosetta or local compiler override is required.
- **Clean build and live demo verified:** all 59 tests passed (41 analyzer, 5 common,
  9 instrumentation, 4 testbed), with no failures/errors/skips. The real closed ring
  reported the expected three executions/units and one queued leaf; the spare-capacity
  root completed four hops with negative reports during its leaf interval.
- This resolves the native generator failure recorded above. Compatibility with the
  existing protobuf/OTel pins was checked; G1–G3 and experimental acceptance remain
  pending. This is demo verification, not independent correctness acceptance.
- Commands, exact run IDs and limits:
  [`docs/native-arm-demo-verification.md`](docs/native-arm-demo-verification.md).

### G1 continuation and live application walkthrough — 2026-10-27

- Causal/lifecycle validation, temporal closed-subset pruning at the selected endpoint,
  and monotonic receipt/evaluation context are implemented with targeted regressions.
  Production reduction is compared with independent exhaustive closure over 64 small
  routing histories, including reordered/duplicate fixed-context replay. See D17/D18.
- The latest full build passed 78 tests (55 analyzer, 5 common, 9 instrumentation,
  9 testbed), with no failures/errors/skips. All three real Chain scenarios ran: closed
  wait produced the expected positive evidence; spare capacity and slow progress
  completed with negative reports during finite leaf work.
- The live demo now accepts scenario selection and 2/3/5-service configurations.
  The standalone analyzer launcher was checked to reach READY on an ephemeral port.
  [`docs/live-application-demo.md`](docs/live-application-demo.md) explains the actual
  data source, commands, application hook locations and supported boundaries.
- **G1–G3 remain unaccepted:** independent gate truth and mapping, bare/instrumented
  equivalence, complete replay/archive and transport/fault acceptance, full historical
  endpoint search and proof review are still required before integration acceptance,
  evaluation and clean-environment reproduction. No research task is waived by the
  live demonstration. The manuscript and full evaluation remain unfinished.

### Historical Jobin stage-0 handoff — preserved 2026-10-29

The merged Jobin history independently records Dev B's A-BOOT verification and
A-SPEC lifecycle acceptance against `21c8fb4`, plus the 23-case test-only B-FEAS
probe at `c5ab975`. See `docs/bootstrap-verification.md`, `docs/b-feas-handoff.md`
and `docs/dev-b-stage0-readiness.md`. Their statements that Dev C review/G0 were
pending describe that earlier branch snapshot; the later D13/D14 solo review and
stage-1 inventory above remain the implementation baseline. The imported evidence
does not establish independent Dev C review or G1–G3 acceptance.
### Final demo — 2026-10-29

This integrated implementation follows the [final-demo plan](docs/superpowers/plans/2026-10-29-final-demo.md).
Implementation status is distinct from verification and gate acceptance.

| Task | Owner | Status on this branch |
|---|---|---|
| T1 — Boutique scenarios and snapshot fields | Dev 1 | Implemented; combined Java/API checks passed |
| T2 — Upstream ad-service adaptation | Dev 1 | Implemented; source provenance recorded |
| T3 — Edge driver, deployment and injection | Dev 1 | Implemented; combined Java/API checks passed |
| T4 — Monitor process/control integration | Dev 1 | Implemented; combined Java/API checks passed |
| T5 — Boutique real-process evidence tests | Dev 1 | Implemented; real-process evidence tests included |
| T6 — Pinned Compose store and reconnect measurement | Dev 1 | Implemented; Dev 1 Docker verification recorded |
| T7 — Flat monotone monitor layout | Dev 2 | UI implementation added; final browser rehearsal pending |
| T8 — Source toggle and Boutique UI | Dev 2 | Implemented; combined API/DOM checks passed; visual qualification pending |
| T9 — Real litmus integration tests and CI | Dev 2 | Implemented, pre-G1, not accepted; two local integration tests passed |
| T10 — README, runbook and status | Dev 2 | Reconciled with both lanes; combined qualification record in progress |
| T11 — Clean-clone rehearsal and sign-off | Both | Clean-clone/Docker/15-minute cadence passed; visual matrix and independent sign-off pending |

I-LITMUS/I-CI: **implemented, pre-G1, not accepted** for the two automated litmus
regressions and CI invocation only. Their expected core uses topology and manifest
unit IDs, with no independent truth recorder. Their existing prerequisites and formal
acceptance remain unchanged. G1/G2/G3 are not accepted by this handoff.

Local Dev 2 verification on 2026-10-29 on Darwin arm64
(`Ananyas-MacBook-Air.local`): Java 21.0.13 build passed 106 tests;
`spotlessApply` and `integrationTest` passed, with two real litmus tests. Nine Node
DOM tests passed. CI is configured for Node 22 DOM tests and artifact uploads;
remote CI execution is not claimed. No browser was available, so desktop/mobile light/dark visual
verification remains pending alongside T11 clean-clone/Docker rehearsal. See the
[runbook verification record](docs/final-demo-guide.md#verified-on) for the JDK version
and scope of these checks.

The remaining backlog is A-TRUTH independent gate truth, A-FAULT/I-FAULT fault runs,
bare/instrumented equivalence, B-INTEROP SDK retry/partial-success, C-REPLAY archive,
C-TIME full endpoint search, C-PROOF, G1/G2/G3 reviews, baselines (E-*), eval workspace,
statistics, reproduction package and paper. The [runbook](docs/final-demo-guide.md)
records the pending rehearsal and session/clock limits.

## 1. Ownership and working rules

### Dev A operator-demo continuation — 2026-10-27

- Per the owner's explicit monitor request, a separate killable workload JVM now
  exposes its PID plus timestamped JDK process/worker diagnostics. The interactive
  closed ring has no RPC deadline or eight-second stop; finite CLI runs and controls
  retain deadlines. See D20 and `docs/live-monitor-design.md`.
- The main display explicitly names confirmed deadlocks and their workers/slots,
  shows wait-for dependencies and retains the evidence after application exit. Exit
  ends the running incident without manufacturing a confirmed negative report.
- The full build passed with 20 testbed tests, including real gRPC/OTLP process
  isolation, persistence beyond the former stop window, PID termination, both
  finite controls, actual thread/slot binding and the completion/new-run race.
  Browser verification also exercised abrupt SIGKILL exit and retained evidence.
- This is A-TESTBED/operator-demo usability work, not acceptance of A-TRUTH,
  I-LITMUS/I-CI, G1–G3, TeaStore integration, pre-deadline experiments or a proof.

| Developer | Primary ownership | Delivers to others |
|---|---|---|
| **Dev A — system and truth** | Build/CI, testbed/driver, independent ground truth, workload and fault harness, experiment runs | Runnable supported deployment; truth/scenario artifacts; integration runs and raw data |
| **Dev B — instrumentation** | Gate/worker accounting, RPC hooks, event DTOs, leases/checkpoints, exporter, runtime overhead and packaging | Versioned event contract; observation fixtures; instrumented adapter and telemetry |
| **Dev C — analyzer and evidence** | Report/replay contract, receiver, certified cuts, reduction/time, proofs, analyzer-side baselines and statistics | Replay validator/oracle; analyzer binary; verdicts, proof notes and figures |

Each row has **one accountable owner**. Reviewers and joint gates do not create
ambiguous shared implementation ownership. B owns event portions of common; C owns
report portions. All three approve schema semantics at G0. A never injects truth into
analyzer inputs; C never imports testbed/instrumentation.

**Within each lane:** follow the dependency column, not merely table position.
**Across lanes:** a task may start once its listed artifacts are accepted; no need to
wait for unrelated work in the same stage. Draft contract discussions/proof sketches
can run in parallel, but implementations cannot assume an unapproved schema.

## 2. Overall order and critical handoffs

| Stage | Dev A | Dev B | Dev C | Exit / consumers |
|---|---|---|---|---|
| 0: feasibility and contract | Bootstrap + independent truth/scenario specification | Real gRPC feasibility + event-contract draft | Model/counterexamples + report/replay draft + literature | **G0** freezes reviewed v2; all implementation lanes depend on it |
| 1: build independent layers | Bare supported testbed + truth + deterministic scenarios | DTOs → gate → RPC hooks/scanner/export | DTOs/replay → ingest → cuts → reducer/time/report/proof | **G1**: truth and observations reconcile; analyzer works against synthetic evidence |
| 2: integrate | Instrumentation switch + fault runs | SDK/transport and cancellation integration fixes | Analyzer/verdict integration fixes | **G2**: real litmus/adversarial tests in CI |
| 3: compare | Completed traces, realistic workload, experiment protocol | Live spans, metrics, buffering, overhead | Invocation/DDMon adaptations and isolated ablations | Accepted baseline outputs and fair-comparison protocol |
| 4: evaluate and publish | Full runs + raw data | Reproduction package | Statistics + final claim audit/manuscript lead | **G3** and paper decision |

Critical path:
`A-BOOT → B-FEAS → B-CONTRACT → C-CONTRACT → G0 →
B-TYPES → B-GATE → B-RPC/B-SCAN/B-EXPORT → B-INTEGRATE → G1 →
I-LITMUS → I-FAULT → G2 → E-RUN → E-STATS → E-REPRO → G3 → P-PAPER`.

C's analyzer/proof path and A's independent-truth path must also reach G1; speeding up
only B's lane cannot bypass those dependencies. No fixed eleven-week promise: estimate
implementation duration after B-FEAS and G0, and reserve explicit integration/proof time.

## 3. Stage 0 — Feasibility, research model and contract

| ID | Owner | Depends on | Work | Done / handoff |
|---|---|---|---|---|
| A-BOOT | A | none | Gradle modules, catalog compatibility, formatting, existing proto compilation and CI | Empty modules build; real tests execute; bootstrap guards removed once their replacement works; build available to B/C |
| A-SPEC | A | none | Specify supported scenario lifecycle, truth identities/clock alignment and bare/instrumented equivalence | Reviewed truth/scenario document independent of event pipeline; onset uncertainty defined; consumed by B-FEAS/C-MODEL |
| C-MODEL | C | none | Formalize manifest scope, closed-wait predicate, complete prefixes, temporal pruning and v1 counterexamples | Model/proof outline, independent oracle design; no unsupported S1/S2/L1 or irreducibility claim |
| R-LIT | C | none | Verify closest papers and outstanding citations; update comparison/claim table | DDMon proxy/replication/timeouts accurately represented; unverified sources marked; A/B review deployment and telemetry distinctions |
| B-FEAS | B | A-BOOT, A-SPEC | Real gRPC prototype: early stream identity, application gate, one job/slot, blocking helper, response trailers and cancellation | Demonstrate saturated queue visibility before application span; response-before-exit; no blocking app work on transport threads; report exact hook lifetimes to A/C |
| B-CONTRACT | B | B-FEAS, C-MODEL | Review design §4's 13-type v2 wire contract, manifest, IDs and clock/sequence semantics | Complete event examples and producer state machine; A/C review; draft handed to C-CONTRACT before freeze |
| C-CONTRACT | C | B-CONTRACT, A-SPEC | Finalize report/replay contract and evaluation envelopes; plan v1 migration | Golden JSON, missing-evidence vocabulary, response/completeness fixtures; existing frozen YAML changed only in the contract PR; choose JSON or explicitly approve YAML dependency |
| G0 | B | A-BOOT, A-SPEC, C-MODEL, R-LIT, B-FEAS, B-CONTRACT, C-CONTRACT | Contract-tagged all-developer review | A/B/C sign-off recorded; version 2/event/report/replay migration agreed; decisions appended; only now freeze implementation contracts |

**G0 failure:** correct the specific feasibility/model problem and review the changed
contract. Do not silently emulate missing evidence, relabel callbacks as executions,
or proceed with a partially compatible v1/v2 protocol.

## 4. Stage 1 — Parallel implementation with stable contracts

### Dev A lane

| ID | Depends on | Work | Done / handoff |
|---|---|---|---|
| A-TESTBED | G0, B-TYPES | Implement generic unary Chain adapter, explicitly bounded application gate deployment, 2/3/5-instance config and root driver | Bare chain runs; deadlines propagate; retries/hedging policy enforced; seed/config/manifest recorded |
| A-TRUTH | A-TESTBED, A-SPEC | Independent transition/call recorder and post-run truth merger | Own IDs/counters; no SpanLease events or analyzer logic reused; physical slot state and onset interval independently validated |
| A-TRIGGER | A-TRUTH | Barrier-coordinated closed-wait trigger, saturation, slow-progress and spare-capacity scenarios | Trigger repeatable; barriers released before measured deadlock; exact expected residual set and slots recorded |
| A-FAULT | A-TRIGGER | Seeded observation fault proxy and crash/cancel schedules | Separate true history and delivery faults; interior/tail loss, duplication, reordering, skew, crash and partition reproducible |
| A-HARNESS | A-TRIGGER, C-TYPES | Manifest/observation/truth/verdict artifact collection and scoring skeleton | One-command finite run; schema validation; truth mapping evaluator-only; accepts C-REPORT outputs without analyzer imports |

A-TESTBED initially uses a simple uninstrumented gate with the same admission/worker
semantics. It must not wait for B-GATE. Replace with B's optional instrumentation path
at B-INTEGRATE, and verify equivalence rather than maintaining two diverging runtimes.

### Dev B lane

| ID | Depends on | Work | Done / handoff |
|---|---|---|---|
| B-TYPES | G0 | Implement common event/manifest DTOs, keys, IDs and validation | Stable public types/fixtures for C; no grpc/SDK dependency in common |
| B-GATE | B-TYPES, B-FEAS | SlotTrackingExecutor with instance-local transition/sequence discipline, bounded queue and actual-exit release | R1/R3 plus I1–I4 tests; overflow/rejection and exceptions cannot leak slots; independent gate interface for A |
| B-RPC | B-GATE | Early stream hook, Context, generated blocking helper integration, request/response metadata, cancellation | R2/R14/R16 and I5 tests; no duplicate terminal block; response versus local completion distinguished |
| B-SCAN | B-GATE | Startup init, idle checkpoints, atomic block leases, epoch/loss accounting | R8/R15 tests; no lease after block closure; checkpoint exposes a dropped suffix; virtual-time fixtures given to C |
| B-EXPORT | B-TYPES | Dedicated SDK LogRecord encoding/export, bounded offers and observable losses | SDK round-trip fixtures; no wait for queue capacity; byte/queue-loss counters; producer overflow leaves sequence holes |
| B-INTEGRATE | B-RPC, B-SCAN, B-EXPORT, A-TRIGGER | Instrumented/bare testbed switch and event-to-truth fidelity run | P1 queue observation, P2 occupancy, P3 joins; terminal and init/checkpoint order match independent truth; handoff to G1 |
| B-INTEROP | B-EXPORT, C-INGEST | Real SDK → receiver transport compatibility | Overload retry, partial success, malformed batch, duplicate and message-size behavior tested; no assumed losslessness |

### Dev C lane

| ID | Depends on | Work | Done / handoff |
|---|---|---|---|
| C-TYPES | G0, B-TYPES | Common verdict/coverage DTOs and deterministic JSON | Golden round-trip reports conform to design §8; available to A-HARNESS |
| C-REPLAY | C-TYPES, C-CONTRACT | Replay generator/validator with manifest, receipt/evaluation context and independent oracle | Migrated v2 init/checkpoint/response fixtures; old v1 not silently accepted; invalid true histories rejected |
| C-INGEST | C-TYPES | OTLP decoder/receiver, validation, atomic bounded admission, single-writer core | R4/R11/R14/R15; reject unsupported versions; conflicting duplicates invalidate evidence; ready for B-INTEROP |
| C-CUT | C-REPLAY, C-INGEST | Prefix certification, init/UNKNOWN state, gaps, checkpoint freshness and causal closure | R5/R6/R7/R10/R12/R13; send without receive allowed; receive without parent barred; tail loss/idle instance tested |
| C-REDUCE | C-CUT | Complete graph, observed-progress reducer and temporally closed subset support | Empty input/unknown counterpart cannot confirm absence; spare units observed; disjoint cycles and upstream dependents match independent oracle |
| C-TIME | C-REDUCE | Guaranteed interval overlap, exact block identity, lease eligibility and endpoint selection | R9 plus stale-cut ticks, intervening unblock, same nodes/new waits and lease-boundary tests; no global-membership timer |
| C-REPORT | C-TIME, C-TYPES | Four verdicts, evidence set, missing reasons and deterministic emission | Golden reports; exact fixed-query replay; both confirmations coverage-gated; integration binary for A |
| C-PROOF | C-TIME, C-MODEL | S1/S2, termination/order independence, temporal-pruning argument and conditional L1/timing review | Written proof reviewed by A/B; bounded exhaustive oracle checks are separate evidence; any counterexample fixed before G1 |

C implements against synthetic evidence and does not need B's exporter to build
C-CUT/C-REDUCE. B needs the report semantics only for interoperability diagnostics.
Proof sketches can be developed earlier; C-PROOF is final acceptance against the actual
implemented algorithm.

### Gate G1

| ID | Owner | Depends on | Done |
|---|---|---|---|
| G1 | C | A-HARNESS, A-TRIGGER, B-INTEGRATE, B-INTEROP, C-REPORT, C-PROOF | Independent truth/event fidelity accepted; receiver compatible; synthetic counterexamples pass; proof/timing assumptions reviewed; A/B/C approve integration readiness |

## 5. Stage 2 — End-to-end integration and CI

| ID | Owner | Depends on | Work / done |
|---|---|---|---|
| I-LITMUS | A | G1 | Real gRPC → OTLP → analyzer: deterministic trigger yields exact expected evidence set; fully observed spare-capacity pattern yields CONFIRMED_NO_DEADLOCK; B/C fix their layers |
| I-FAULT | A | I-LITMUS, A-FAULT | Real cancellation/response/release, unknown slot, lost final record, stale checkpoint, crash, unsupported route and skew tests; guarantee cases separated from outside-assumption experiments |
| I-CI | A | I-FAULT | Both litmus tests and targeted integration regressions in Gradle integrationTest/CI; no bootstrap skips, sleeps-for-sync or truth leakage |
| G2 | A | I-CI | A/B/C accept real end-to-end correctness evidence; baseline/evaluation freeze records commit and parameter set |

Changes after G2 that affect predicates, instrumentation or schema rerun the relevant
unit/property/race tests and both litmus integrations before experiments resume.

## 6. Stage 3 — Baselines, realism and experimental protocol

| ID | Owner | Depends on | Work / done |
|---|---|---|---|
| E-COMPLETED | A | G2 | Completed-span/timeout baseline; same workloads, deadline origin and truth; latency measured rather than assumed |
| E-REALISM | A | G2, R-LIT | Supported application or trace-derived topology beyond constructed cycles; provenance and synchronous-scope transformations documented |
| E-LIVE | B | G2 | Ordinary live spans and enriched live spans with identical queue/slot facts → same C analyzer; unknown fields remain unknown; overhead separately reported |
| E-METRICS | B | G2 | Metrics-only saturation/in-flight baseline, custom/JMX attribution, no execution-localization claims from counts |
| E-BUFFER | B | G2, R-LIT | Hindsight-style early-triggered buffering comparison or documented infeasibility; trigger delay and eviction included |
| E-RPC | C | G2, R-LIT | Generic invocation monitor plus carefully specified DDMon-inspired comparison; supported cases/adaptations/source differences explicit; no inherited proofs |
| E-ABLATE | C | G2 | Capacity/lease/causality mechanisms independently ablated; retain all other guards and report actual effect, including no safety change |
| E-PROTOCOL | C | G2, E-REALISM | Freeze metrics, independent-run units, precision/power plan, parameter/fault matrix, clock/latency uncertainty and baseline fairness; A/B review |
| E-COST | B | E-PROTOCOL, B-INTEGRATE | Bare versus instrumented observer-effect experiment under ordinary/degraded load; equal execution architecture, CIs and queue/CPU/p99/bytes measurements |

Scope-control rule: do not add ML/RCA, async support, remediation, a GUI, or unrelated
benchmarks to inflate novelty. Strengthen correctness and the closest comparison first.

## 7. Stage 4 — Runs, reproducibility and paper

| ID | Owner | Depends on | Work / done |
|---|---|---|---|
| E-RUN | A | E-COMPLETED, E-REALISM, E-LIVE, E-METRICS, E-BUFFER, E-RPC, E-ABLATE, E-PROTOCOL, E-COST | Full preregistered matrix, seeds/commits/manifests and raw artifacts archived; no discarded unfavorable outcomes without explanation |
| E-STATS | C | E-RUN | Reproducible figures/CIs, both false-confirmation directions, exact residual-set accuracy, intervention time and inconclusive causes; zero failures gets one-sided upper bound |
| E-REPRO | B | E-STATS | Pinned clean-environment build and per-figure reproduction commands; A or C independently reproduces outputs |
| G3 | A | E-REPRO, C-PROOF | A/B/C sign off measured artifacts, claim-to-evidence table, limitations and reproducibility; unsupported claims removed |
| P-PAPER | C | G3, R-LIT | Lead manuscript; A writes workload/truth/evaluation methods, B instrumentation/cost/artifact, C model/proof/results/related work; all review actual venue call/track before choosing full versus preliminary paper |

A submission, upload or external publication is a separate user-directed action.
Completing these tasks does not authorize submitting a manuscript automatically.

## 8. Handoff contracts and change management

| Handoff | Producer → consumer | Minimum accepted artifact |
|---|---|---|
| Runtime semantics | B-FEAS → A-TESTBED, C-MODEL/G0 | Hook timeline, supported deployment, cancel/response/slot-lifetime demonstration |
| Event contract/types | B-CONTRACT/B-TYPES → C, A | Schema/version, typed DTOs, valid and invalid golden records |
| Replay/report contract | C-CONTRACT/C-TYPES → B, A | Manifest/envelope/report examples with missing-evidence cases |
| Truth/scenarios | A-TRIGGER → B-INTEGRATE, I-LITMUS | Seeds, independent truth IDs, expected residual/slots, onset uncertainty |
| Real observations | B-INTEGRATE → C/I-LITMUS | Exported logs plus run configuration, never injected truth |
| Analyzer outputs | C-REPORT → A-HARNESS | Versioned deterministic reports, exact scope and query context |
| Experiments | E-RUN → E-STATS → E-REPRO | Immutable raw data/commit/config/seed → scripts/figures → clean reproduction |

Short-lived task branches; PR reviewed by another lane. Contract changes require all
three developers, contract label, compatible fixture migration and schema bump when
wire-visible. Append decisions in the same change. Producer changes that break a
handoff block dependent tasks until the reviewed contract and fixtures are updated.

Every R1–R16 owner is mapped in design §7. Public behavior and state-machine changes
ship with the smallest relevant test in the same PR; no broad test rewrite is needed
for prose-only edits. Run build plus affected module tests, and integrationTest once
available for changes touching end-to-end behavior.

## 9. Agent execution instructions

Read `AGENTS.md`, requirements, design, this task/dependency plan and decisions before
coding. Select an assigned task whose prerequisites are accepted; identify its owner,
required handoff, touched invariants and proof/test obligation. Do not invent readiness
or start a consumer implementation from an unapproved contract.

Use the fixed stack, module boundaries and defaults; keep unknown evidence explicit,
separate observed ownership from freshness, and preserve the response/cancel/exit
distinction. Record seeds and replay context. No generated source stubs for another
lane's unfinished interface, no truth imports, no unsupported novelty or timing claims.

A code handoff reports the implemented behavior, task ID, verification commands/results,
contract/race implications and any material remaining dependency. A documentation task
does not claim Java tests ran when the wrapper/implementation does not yet exist.
