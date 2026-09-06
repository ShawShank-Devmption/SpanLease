# SpanLease — Task Breakdown (3 developers)

> Derived from `design.md` §11 phases. Read `requirements.md` + `design.md` first.
> Section numbers like "design §7" refer to `design.md`.

## 0. Team & ownership lanes

Three lanes chosen so each dev owns a coherent slice with a stable interface to the
others, and the only shared artifact early on is the `common` module (event schema).

| Dev | Lane | Owns (modules) |
|---|---|---|
| **Dev A** | Testbed & ground truth | `testbed/`, workload driver, deadlock trigger, ground-truth recorder, eval harness runners |
| **Dev B** | Instrumentation & telemetry | `instrumentation/` (interceptors, SlotTrackingExecutor, lease scanner, priority exporter) |
| **Dev C** | Analyzer & verdicts | `analyzer/` (ingest, cuts, graph, CONFIRM, coverage, verdict reports), `baselines/` analyzer-side |

Shared (all three, changes require sign-off from all): `common/` event schema, verdict
JSON format (design §8), this file and design.md.

**Integration contract:** Dev B and Dev C never need each other's code to make progress —
they meet only at the wire contract (design §4) and the verdict JSON (design §8). Dev C
develops against **synthetic observation logs** until P5 integration; Dev A supplies the
real system and the truth to score against.

---

## Phase 0 — Foundations (all devs, ~week 1)

**Goal:** repo skeleton + the frozen contract, so all three lanes can proceed in parallel.

| ID | Task | Owner | Done when |
|---|---|---|---|
| T0.1 | Gradle multi-module skeleton per design §2 layout (repo root = Gradle root, D1); wire modules to `gradle/libs.versions.toml` (verify latest compatible patches once, D2); spotless/google-java-format; CI workflow already exists at `.github/workflows/ci.yml` — remove its bootstrap guards once `./gradlew build` is green on empty modules | A | CI green |
| T0.2 | `common`: `SpanLeaseEvent` typed model, attribute-key constants, id formats, LogRecord ↔ event serialization both ways, `schema_version=1` | B | Round-trip property test green |
| T0.3 | `common`: verdict/coverage report JSON model (design §8) + serialization | C | Golden JSON test green |
| T0.4 | Wire the frozen proto (`testbed/src/main/proto/spanlease/testbed/chain.proto`, D4) into the build via protobuf-gradle-plugin | A | Generated stubs compile |
| T0.5 | Contract freeze review: all three sign off on design §4 tables | all | Recorded in PR description |

**Phase exit:** any module can depend on `common` and build; contract frozen at v1.

## Phase 1 — Parallel core builds (P0 + P1/P2 + analyzer core, ~weeks 2–4)

### Dev A (proposal P0)
| ID | Task | Done when |
|---|---|---|
| A1.1 | Three gRPC services with plain bounded executors (no instrumentation yet); config class per design §4 | Chain call A→B→C works end to end |
| A1.2 | Workload driver: open/closed-loop load, seeded RNG, per-request outcome log | Load sweep runs reproducibly under a seed |
| A1.3 | Deterministic deadlock trigger: barrier-controlled A→B→C→A saturation pattern, seeded, with configurable cycle length {2,3,5} and k per service | Deadlock forms on demand, reproducibly |
| A1.4 | Ground-truth recorder (design §6.4): in-process hooks, per-instance truth log, post-run merger computing onset + member set (executions + units) | **P0 exit:** truth names exact executions/units with zero SpanLease dependence |
| A1.5 | Hard-negative scenarios: slow-but-progressing dep; transient saturation; cyclic pattern with spare capacity; partial pool exhaustion | Each scenario scripted + truth-labelled |

### Dev B (proposal P1 + P2)
| ID | Task | Done when |
|---|---|---|
| B1.1 | `SlotTrackingExecutor` per design §6.1 — single-lock assign/queue decision, slot free-list, invariants I1–I3 | Unit + stress tests: no double-assign, seq gap-free, release-after-terminal |
| B1.2 | Server transport interceptor emitting `rpc.invocation.arrived` pre-dispatch; `resource.wait.begin` from the executor | **P1 exit:** test proves a queued invocation is observable with no execution_id and no span |
| B1.3 | Slot acquire/release events with stable `resource_instance_id` | **P2 exit:** replayed events reconstruct occupancy == executor's own accounting for a full stress run |
| B1.4 | Priority exporter: dedicated OTLP logs pipeline, bounded queue, drop counter, never blocks app threads (I4) | Overflow test: app latency unaffected, drops counted |
| B1.5 | Race-matrix tests for R1, R3, R4 (design §7) at this layer | Each race has a targeted test |

### Dev C (analyzer core, against synthetic logs)
| ID | Task | Done when |
|---|---|---|
| C1.1 | Synthetic observation-log generator implementing the frozen scenario schema (`analyzer/src/test/resources/replays/SCHEMA.md`, D5; worked example checked in) — this is Dev C's testbed until P5 | The 5 canned scenarios listed in SCHEMA.md pass generator validation |
| C1.2 | Ingest: OTLP logs receiver, parse/validate, bounded MPSC queue, single-writer core thread (design §6.5, R11) | Receiver accepts OTel SDK export; unknown schema_version rejected |
| C1.3 | Dedup + per-instance ordering + frontier/gap tracking with Δ aging (R4, R5, R12) | Property test: verdict invariant under permutation of concurrent events |
| C1.4 | LATEST_STABLE_CUT (design §5.6) | Deterministic-given-observations test; send-without-arrival closure test |
| C1.5 | Graph builder (design §5.2) — executions, queue entries, unit nodes; live-edge rules incl. lease-expiry edge drop (R7) | Golden replay tests per scenario |

**Phase exit:** P0/P1/P2 exit criteria met; analyzer ingests synthetic streams into correct graphs.

## Phase 2 — Wait edges, leases, predicate (P3 + P4 + P5 algorithm, ~weeks 5–6)

### Dev A
| ID | Task | Done when |
|---|---|---|
| A2.1 | Failure-negative scenarios: crash a service mid-deadlock; partition; suppress `execution.end`/`resource.release` via config hook | Scripted + truth-labelled |
| A2.2 | Fault-injection proxy shim for the analyzer's OTLP ingress (seeded loss/delay/reorder/dup/skew) — design §10 | Deterministic under seed |
| A2.3 | Eval harness v1: run scenario → collect verdict stream + truth → score timeliness + localisation metrics (design §9.4) | One-command scenario run producing a metrics JSON |

### Dev B
| ID | Task | Done when |
|---|---|---|
| B2.1 | Client interceptor: invocation_id generation, metadata propagation, `sent`, `block.begin/end` with outcome mapping, Context key for caller execution_id (I5) | **P3 exit:** client edges join server counterparts on invocation_id; zero unmatched edges nominal |
| B2.2 | Cancellation path: `execution.cancel` from deadline propagation + queued-entry cancel under executor lock (R2, R3, R16) | Targeted race tests green |
| B2.3 | Lease scanner (design §6.3): age threshold, renewal, `start_ref`, expiry_factor; GC-pause tolerance (R8) | **P4 exit:** event volume measured as function of threshold/interval |
| B2.4 | Wire instrumentation into Dev A's testbed behind a flag (`--instrumented`) | Testbed runs both bare and instrumented |

### Dev C
| ID | Task | Done when |
|---|---|---|
| C2.1 | CONFIRM + CAN_PROGRESS worklist reduction (design §5.4), C1–C5 | Property test vs oracle evaluator on generated histories |
| C2.2 | Persistence tracking C6 with ±ε narrowed-overlap arithmetic (design §5.5, R9) | Unit tests incl. skew boundary cases |
| C2.3 | Coverage accounting + partially-observed-ownership handling (R6, R12, R14): unaccounted units named, conservative-direction rule enforced for **both** confirmed verdicts | Lost-acquire scenario ⇒ `CANDIDATE_INCONCLUSIVE` naming the unit |
| C2.4 | Four-valued verdict emission + evidence core + coverage JSON (design §8); monotonicity property test (less evidence never upgrades a verdict) | Golden verdicts for all canned scenarios |

**Phase exit:** analyzer produces correct verdicts on all synthetic scenarios including
loss/skew cases; instrumented testbed emits full contract.

## Phase 3 — Integration (P5 end-to-end, ~week 7)

All three devs converge. Suggested split: A drives runs, B debugs emission, C debugs verdicts.

| ID | Task | Owner | Done when |
|---|---|---|---|
| I3.1 | End-to-end: instrumented testbed → analyzer; deterministic trigger run | A+B+C | **Litmus 1:** `CONFIRMED_DEADLOCK`, core == ground truth |
| I3.2 | Spare-capacity cyclic pattern run | A+C | **Litmus 2:** `CONFIRMED_NO_DEADLOCK` |
| I3.3 | Uninstrumented-resource route (one service's executor uninstrumented) | B+C | `INSUFFICIENT_OBSERVABILITY` with coverage naming it |
| I3.4 | Fault-matrix smoke: loss/delay/reorder/dup/skew sweeps via A2.2 shim | A+C | No false confirmations in any cell; inconclusive causes reported |
| I3.5 | Both litmus tests wired into CI as `integrationTest` | A | CI runs them on every PR |

**Phase exit:** P5 exit criteria met and in CI.

## Phase 4 — Baselines & ablations (P6, ~weeks 8–9)

| ID | Task | Owner | Done when |
|---|---|---|---|
| E4.1 | Ablation flags in analyzer (`--ablate-capacity/leases/causality`, design §9.2) | C | Each ablation demonstrably fails its designed failure case |
| E4.2 | Metrics-only saturation baseline (custom/JMX depth+in-flight; not attributed to OTel semconv) | B | Runs on identical workload |
| E4.3 | Completed-trace + timeout baseline | A | Runs; latency gap measured |
| E4.4 | In-progress spans → same analyzer (strongest baseline) | B+C | Analyzer held constant; only input model differs |
| E4.5 | Cheriton–Skeen-style RPC wait-for monitor | C | Runs |
| E4.6 | DDMon-inspired passive observer | C | Runs |
| E4.7 | Triggered-buffering (Hindsight-style), where feasible | B | Runs or documented infeasibility |
| E4.8 | Observer-effect experiment: bare vs instrumented cost (CPU/mem/p99/bytes per request) | A | Reported with CIs |

## Phase 5 — Evaluation & reproducibility (P7, ~weeks 10–11)

| ID | Task | Owner | Done when |
|---|---|---|---|
| E5.1 | Full workload/fault matrix runs (design §9.3), repetitions from precision/power analysis | A | Raw results + seeds archived |
| E5.2 | Analysis scripts: all metrics with CIs; one-sided upper bound for zero-observed false confirmations | C | Figures generated from raw data by script only |
| E5.3 | Reproducibility package: pinned deps, one-command `./repro.sh` per figure | B | Clean-machine reproduction of every figure |
| E5.4 | Results writeup feeding the manuscript | all | Every claim maps to a measured artifact |

---

## Working agreements

- **Branching:** trunk-based; short-lived branches `dev{a,b,c}/<task-id>-<slug>`; PR + one
  reviewer from another lane; CI (`.github/workflows/ci.yml`: build, format check, unit +
  property tests, litmus integration tests from phase 3) must pass.
- **Decision log:** every resolved ambiguity, deviation from `design.md`, or contract
  change gets an appended entry in `docs/decisions.md` in the same PR. Before "fixing"
  code that deviates from the docs, check the log — the deviation may be a decision.
- **Contract changes** (`common/`, verdict JSON): PR tagged `contract`, all three approve,
  `schema_version` bump if wire-visible.
- **Race-matrix discipline:** closing a task that touches design §7 requires the matching
  R-test. New race discovered ⇒ add a row to design §7 + a test, in the same PR.
- **No cross-lane imports:** analyzer never imports instrumentation/testbed (design §2 rule).
- **Determinism first:** every scenario, workload, and fault injection takes a seed; a bug
  report without a seed + scenario file is incomplete.

---

## LLM system prompt (give this to Codex / Claude Opus verbatim)

Paste the following as the system prompt for any code-generating LLM session on this
project. It keeps three parallel streams stylistically and architecturally mergeable.

```text
You are generating code for SpanLease, a research-grade distributed-deadlock monitor for
Java/gRPC services. Before writing code, consult requirements.md (domain), design.md
(architecture, algorithms, invariants), tasks.md (your task's scope), and
docs/decisions.md (resolved ambiguities — check it before "fixing" any apparent
deviation from the docs). The proposal PDF is the ultimate authority; if your task seems
to conflict with design.md, stop and say so instead of improvising.

HARD RULES
1. Language/stack: Java 21, Gradle (Kotlin DSL), grpc-java with BLOCKING stubs only,
   OpenTelemetry SDK for export, JUnit 5 + AssertJ + jqwik for tests, Jackson for JSON.
   Dependency versions come ONLY from gradle/libs.versions.toml — never write a version
   literal in a build file. No Spring, no Lombok, no Guava, no new dependencies without
   a design.md change.
2. Style: google-java-format formatting. Immutable data as Java records. Explicit types
   over var in public signatures. Package prefix dev.spanlease.<module>. One top-level
   class per file. Javadoc on every public type stating its invariants, not restating
   its name.
3. Module boundaries (never violate): common has no grpc/testbed deps; analyzer NEVER
   depends on instrumentation or testbed — it consumes observations only. Ground truth
   never flows through analyzer inputs.
4. The wire contract (design.md §4) and verdict JSON (design.md §8) are frozen. Do not
   add, rename, or repurpose fields; propose a contract PR instead.
5. Concurrency invariants I1–I5 and race rows R1–R16 in design.md §7 are normative.
   Every state transition and its local_seq assignment happen inside one critical
   section (I1). Instrumentation never blocks application threads (I4). The analyzer
   core is single-writer: only the core thread mutates analyzer state; producers only
   enqueue.
6. Conservative-direction rule: under ANY ambiguity (missing event, expired lease,
   frontier gap, unobserved unit, persistence < tau) the analyzer outputs an
   inconclusive verdict naming the missing item. Never treat an unobserved unit as free
   or as held. Both CONFIRMED verdicts require complete observed evidence.
7. Time: local_seq gives LOCAL order only; cross-host order comes only from the causal
   relation (send -> arrival). wall_time is used exclusively for persistence thresholds
   with +/- epsilon narrowing. Never compare wall_time across hosts for ordering.
8. Determinism: all randomness behind a seeded java.util.random.RandomGenerator injected
   via constructor. No Thread.sleep in tests for synchronization - use latches/barriers
   or virtual time. Analyzer results must be a pure function of the observation set.
9. Errors: no swallowed exceptions; no bare catch(Exception). Instrumentation failures
   degrade telemetry (count + coverage marker), never the application. Analyzer rejects
   malformed events with a counter, never crashes the pipeline.
10. Tests ship in the same change as the code. A task touching design.md §7 includes its
    R-row test. Public behavior gets a test at the smallest layer that can express it.
    The two litmus tests (deterministic-trigger => CONFIRMED_DEADLOCK with exact core;
    spare-capacity cycle => CONFIRMED_NO_DEADLOCK) must never break.
11. Keep it lean: no speculative config, no interfaces with one implementation, no
    frameworks, no TODO-stubs for other lanes' work. Build exactly what the task asks,
    complete and runnable.
12. Comments state non-obvious invariants or cite design.md sections (e.g. "// I1:
    seq assigned under slot lock"); they never narrate what the next line does.

OUTPUT EXPECTATIONS
- Compilable, formatted code with its tests, plus the exact gradle command to run them.
- If the task is ambiguous or requires a contract/design change, output the question or
  the proposed design.md diff INSTEAD of guessing code.
```
