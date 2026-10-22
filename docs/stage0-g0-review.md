# Stage-0 and G0 solo-role review

Date: 2026-09-22. Execution model: one implementer exercising the distinct Dev A, B
and C responsibilities under decision D13. This is role-based self-review, not an
independent three-person review.

## Prerequisite disposition

| Task | Evidence | Disposition |
|---|---|---|
| A-BOOT | `docs/bootstrap-verification.md`; Java 21 clean build and CI | accepted |
| A-SPEC | `docs/scenario-truth-spec.md`; B lifecycle and C truth/clock review recorded | accepted |
| C-MODEL | `docs/model-mvp.md`; exhaustive truth oracle and temporal-subset counterexamples | accepted for initial implementation |
| R-LIT | `docs/literature-review.md`; DDMon positioning and unverified-source labels retained | accepted for implementation; publication claim audit remains G3 work |
| B-FEAS | `GrpcGateFeasibilityTest`; hook timeline below | accepted |
| B-CONTRACT | design section 4 plus producer state machine below | accepted as schema v2 implementation baseline |
| C-CONTRACT | design section 8 plus replay/report decisions below | accepted as schema v2 implementation baseline |

## B-FEAS hook timeline

The real grpc-java test uses a `ServerStreamTracer` for pre-dispatch invocation metadata,
a separate gRPC callback executor, a one-slot bounded application gate, a blocking unary
downstream stub and response-close trailers.

Observed and asserted order:

1. The stream tracer records arrival and inserts invocation context before the generated
   unary callback runs.
2. The callback submits exactly one job and returns; a second callback runs and records a
   queued request while the first application job owns the only slot.
3. The blocking downstream stub executes on the named application worker, while the
   downstream callback uses its separate executor.
4. Response trailers are inserted at close. The first blocking client returns while its
   server job still owns the slot; `end` and `release` occur only after an explicit cleanup
   latch is released.
5. Client cancellation is delivered while the application worker remains occupied.
   Ownership persists until the job actually exits and the gate records release.

The feasibility gate is test-only and does not define B-GATE's production API. Its
coordination uses latches and bounded awaits, never sleeps.

## C-MODEL disposition

The supported snapshot domain contains blocked executions, queued ready requests,
selected-pool eligible units, unit ownership and exact invocation counterparts. The
independent oracle enumerates all nonempty candidate subsets and directly evaluates the
closure predicate. It does not invoke a production worklist or graph helper.

The model cases cover a closed k=1 ring, spare capacity, a nonblocked owner, disjoint
cycles and upstream dependents. Temporal qualification selects the latest endpoint at
which a closed subset has complete assertion overlap for tau; a newer upstream waiter
therefore cannot reset an older persistent cycle.

The v1 counterexamples remain:

- no `resource.init` permits unseen capacity to be mistaken for absence;
- no checkpoint permits an undetected lost suffix;
- no `rpc.response.sent` parent permits cross-host response order to be invented;
- cancellation cannot stand in for actual exit/release;
- repeated equal node membership cannot prove uninterrupted persistence.

The executable model is bounded evidence, not a proof of S1/S2/L1. C-PROOF remains a G1
prerequisite against the production implementation.

## B-CONTRACT producer state machine

Schema version 2 and the thirteen payloads in design section 4 are frozen for the first
implementation. Every envelope carries instance epoch, positive local sequence, optional
exact causal parent, qualified wall time and process-local monotonic time. Producers
serialize state transition, sequence allocation and nonblocking publication attempt.

Per inbound job:

```text
arrived -> wait.begin -> acquire -> [block.begin -> sent -> response.sent -> block.end]*
        -> [cancel requested] -> execution.end -> resource.release
```

The supported first version permits at most one open downstream block per execution and
new invocation identity for every sequential retry. `resource.init` precedes admission;
idle checkpoints continue. A failed publication keeps its sequence position and is
exposed by a later checkpoint. Response, block end, cancellation, execution end and
release remain separate transitions.

Invalid IDs, nonpositive sequences, unsupported versions, impossible transitions,
conflicting duplicates and malformed causal references invalidate affected coverage.
Unknown values are never synthesized.

## C-CONTRACT replay and report decisions

New fixtures use JSON through the existing Jackson dependency. The historical v1 YAML
is retained only as a named incompatible fixture; production replay must reject it rather
than reinterpret it. A deterministic replay input consists of the immutable manifest,
received envelopes with receipt times, evaluation times and configuration.

Reports use the four verdict names and fields specified in design section 8. Arrays are
sorted. Both confirmed verdicts require complete whole-manifest evidence in the initial
implementation. Missing-evidence identifiers are stable strings but additions remain
backward-compatible within schema v2; changing field meaning or required structure
requires a new schema version and contract review.

## G0 role sign-off

- Dev A perspective: supported test lifecycle, truth separation and required scenarios
  reviewed; accepted by the sole implementer under D13.
- Dev B perspective: gRPC feasibility and producer lifecycle reviewed with executable
  evidence; accepted by the sole implementer under D13.
- Dev C perspective: predicate, conservative verdict guards, replay context and report
  semantics reviewed; accepted by the sole implementer under D13.

G0 is approved for solo implementation of schema v2 in this working tree. The approval
becomes a durable team handoff only when committed; it must never be described as
independent multi-person review. G1–G3, real integration evidence, proofs and publication
claims remain pending.
