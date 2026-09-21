# SpanLease technical and requirements review

> Historical review of the pre-revision specification. The revised requirements,
> design and task plan address these findings as of decision D8; v2 still awaits G0
> contract approval and implementation/proof validation. Section/task references below
> refer to the reviewed older documents, not the replacement task IDs.

Review date: 2026-09-19. This is a specification review, not a claim that a running
implementation has been verified. The repository contains requirements/design/tasks,
proposal PDFs, a dependency catalog, a proto and replay scaffolding. There is no Gradle
wrapper, Java implementation, or runnable test suite yet. CI explicitly skips absent
builds; a green bootstrap workflow is not validation of the system.

Read together with [literature review](literature-review.md) and
[proposed amendments](proposed-amendments.md). The proposal remains authoritative;
proposal conflicts below require explicit resolution, not a silent implementation fix.

## Assessment

The scope, module boundaries, independent ground truth, unsampled custom telemetry,
capacity-aware closure, and explicit inconclusive results are sensible. The design and
tasks broadly cover requirements §§10–11, but are **not technically complete or ready
to implement as written**. Several defects affect both confirmed verdicts and the
claimed intervention window. A novel composition is plausible; correctness and an
evaluation beyond constructed cycles are still missing.

## Findings and traceability

| ID / severity | Requirement and design location | Finding / consequence | Required resolution |
|---|---|---|---|
| F1 / blocking | requirements §7/P1; design §6.2 | Ordinary ServerInterceptor is invoked after dispatch to the server executor. Registering first cannot observe saturation upstream of that executor. | Corrected §6.2; B0.1 must demonstrate a supported pre-dispatch hook and queue association. |
| F2 / blocking | requirements §6 execution; design §6.1 | grpc-java schedules serialized callbacks, not necessarily one Runnable per RPC lifetime. Assigning an execution to each executor task can invent executions, misattribute slots, and delay cancellation behind blocked work. | Trace stream→callback→handler lifetime, Context, close, cancel and actual worker exit at the pinned version before choosing an adapter. |
| F3 / blocking | requirements §7 block events; design §6.2 | ClientInterceptor wraps ClientCall, not generated blocking-stub waiting. onClose is not caller return. | Blocking-call integration spike including immediate response, callback/return races, nested calls and exceptions; clarify logical call wait versus physical park. |
| F4 / blocking | A3/A6; design §§5.6,7 | A contiguous received prefix cannot reveal a missing suffix. With seq 1–20 received and 21 lost, there is no detectable gap until a later record arrives. Leases may stop at completion. | Certified observation horizon, loss accounting and declared instance universe; amendment A-2. |
| F5 / blocking | requirements §8; design §5.4 | Empty B on empty input immediately confirms no deadlock. Missing counterparts satisfy `not in S`; an expired lease can similarly erase a blocking dependency. | Coverage first, explicit unknown states, evidence-backed progress only; A-3. |
| F6 / blocking | A5; design §§4–5 | Capacity configuration declares unit identities but does not observe initial FREE state. A never-used spare slot has no acquire/release event. | Observed initialization or a reviewed initialization contract; never infer free from silence. |
| F7 / blocking | requirements §9 S1; design §§5.1,5.6 | Response→block.end causality is specified but no response-send record or exact propagation mapping is defined. execution.end may occur after a response is sent and received. | A-2 must define an actual send boundary and parent reference; do not equate response with handler return. |
| F8 / blocking | C6; design §§5.5,6.5 | Repeated cuts and unchanged membership do not prove uninterrupted waits. An execution can unblock and block on a different invocation between evaluations. | Full transition history, wait-specific intervals, conservative overlap, recorded evaluation context; A-4. |
| F9 / high | A4; design R9 | An analyzer cannot always detect clock skew beyond ε from these events. Arbitrary skew/loss cannot universally guarantee an inconclusive result. | Separate model guarantees from measured out-of-assumption behavior; add declared health evidence or narrow the guarantee. |
| F10 / high | requirements §9 L1; design §4.1 | τ+Δ+2ε is an unproved persistence premise, not a proved 3.1s implementation latency. Evaluation cadence, first lease, scanner jitter, export and processing delays matter. | Preserve defaults; gate the “27s intervention window” calculation on a timing derivation and measurements. |
| F11 / high | proposal §8.5; design §5.4 | Reduction returns a residual, not necessarily an irreducible core. Two disjoint deadlocks, or upstream waiters feeding a deadlock, survive. | Counterexample tests and explicit residual/witness semantics; A-5. |
| F12 / high | A6 versus I4; design §§4,7 | Nonblocking bounded queues may drop terminals; a promised coverage/drop marker has no event type in the ten-event schema. | Mark affected windows uncertifiable; review how loss becomes analyzer-visible, including a lost final marker. |
| F13 / high | I1; design §§6–7 | Slot lock alone does not synchronize client block, lease scanner, cancellation and per-instance sequence publication. An atomic counter by itself cannot make state+sequence atomic. | Specify one coherent transition/sequence synchronization discipline and lock ordering; scanner tests must exclude leases after close. |
| F14 / high | cancellation; design R2/R3/R16 | Cancellation request is not physical worker termination or slot release. A queued-cancel/assign race can produce acquire followed by cancel legitimately. Causally concurrent terminals need not have a unique “first.” | Per-invocation state machine distinguishes cancel observation, block completion, task exit and release; A-6. |
| F15 / high | determinism; design §§5.6,10 | Gap age and lease expiry depend on receipt/evaluation times, not just the wire-event set. Permuting deliveries can legitimately change intermediate verdicts. | Compare at fixed query cut/horizon and evaluation context; separate final replay invariance from online schedules. |
| F16 / medium | requirement §10 baselines; design §9 | DDMon proxy results do not transfer to a passive Java port. RPC monitor attribution is unverified; live-span baseline could be handicapped by missing facts. | Citation audit plus ordinary/enriched live-span controls, documented adaptations. |
| F17 / medium | requirements §3; design §9.3 | Hedging is outside single-outstanding-wait scope; a free remote replica cannot service an already bound invocation automatically. | Explicit unsupported cases and binding-aware capacity; corrected §9.3. |
| F18 / medium | requirement §10 reproducibility; tasks | No explicit proof/model-checking, publication gates, or task coverage for every R-row; common serialization wording obscures dependency ownership. | New phase-0 gates, proof tasks, R-row coverage map and transport adapters outside common. |
| F19 / medium | design §6.5 | OTLP partial success does not request retransmission of rejected records. | Corrected receiver plan; test retryable overload status and duplicates with actual SDK. |
| F20 / high | requirements §2; proposal §§3,8 | Permanent deadlock language conflicts with RPC deadlines. `any_unit` closure requires exhaustion of each resource pool a queued member needs, although unrelated pools may have spare capacity. | A-1 clarifies persistent circular wait before external timeout, and scopes the saturation statement. |

## Small counterexamples required before implementation

1. **No input:** declared monitored pool, no events. The original algorithm returns
   CONFIRMED_NO_DEADLOCK; the conservative rule forbids that without initialization
   and completeness evidence.
2. **Unknown counterpart:** observed blocked caller, missing server arrival. Absence
   from S is unknown, not evidence that the counterpart can progress.
3. **Lost suffix:** compare a still-blocked history and a history whose final unblock
   and release were lost. Received records can be identical before lease expiry.
   Expiry limits freshness but cannot distinguish those histories before that point.
   Historical confirmation requires a certified earlier interval, not a claim about now.
4. **Disjoint cycles:** `{a↔b}` and `{c↔d}` are closed. Reduction retains all four;
   removing either pair leaves a valid closed witness. “Irreducible by construction”
   is false. A waiter `p→a` also survives although it is not necessary to witness a cycle.
5. **Same members, new waits:** e1/e2 remain in consecutive candidate sets, but an
   intervening block.end/block.begin changes dependencies. Sampled membership cannot
   join the intervals across the transition.
6. **Cancellation without exit:** cancellation is delivered while a handler continues
   cleanup or ignores interruption. Its slot remains held until actual worker exit.
7. **Unused spare slot:** a k=2 pool emits events only for slot 0. Declared capacity 2
   does not observe slot 1 free; the negative litmus needs initialization evidence.
8. **Response before handler return:** client receives response while the server still
   holds its slot for cleanup. Handler execution.end cannot be the response-send parent.

## Validation performed and limits

Read requirements, design, tasks, decisions, proposal semantics/evaluation/reference
sections, pinned dependency catalog, CI, proto and replay schema/example. Inspected
grpc-java v1.68.1 source for dispatch placement and ServerStreamTracer factory API.
Reviewed DDMon technical-report sections relevant to the model, monitoring, limitations
and replication; source depth for other papers is recorded in the literature review.
Documentation consistency and frozen-section preservation are checked separately.
No build, runtime integration, S1/L1 proof, or empirical novelty result is available.
