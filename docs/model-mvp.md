# Pre-G0 model MVP

Status: executable feasibility model, not the production SpanLease detector and not
evidence of G0/G1/G2 approval.

## Purpose

The model MVP makes the proposed closure and conservative verdict rules executable
without freezing the pending v2 event/report contract. It lives entirely in analyzer
test sources and has no dependency on instrumentation, testbed truth, OTLP decoding or
the historical v1 replay fixture.

`TruthClosureOracle` enumerates every nonempty subset of blocked executions and queued
requests in a complete truth snapshot. A subset is closed only when every blocked
execution's counterpart is inside the subset and every eligible unit for each queued
request is owned by an execution inside the subset. The union of all qualifying subsets
is reported as the maximal residual; it is not described as irreducible or minimum.

`ModelDetectorMvp` adds the conservative verdict guards proposed by design section 5:

1. Unsupported deployment produces `INSUFFICIENT_OBSERVABILITY`.
2. Incomplete prefixes, unqualified clocks, stale checkpoints or ineligible block
   leases produce `CANDIDATE_INCONCLUSIVE`.
3. A fully observed empty residual produces `CONFIRMED_NO_DEADLOCK` for that snapshot.
4. A nonempty closed residual is confirmed only when every required block, dependency,
   queue and ownership assertion has a common interval at least tau long.

Run the deterministic demonstration with Java 21:

```sh
./gradlew :analyzer:modelMvp
```

The command prints one `CONFIRMED_DEADLOCK` model result with its exact evidence set
and certified interval, followed by one `CONFIRMED_NO_DEADLOCK` spare-capacity result.
It is human-readable demonstration output, not the pending verdict JSON contract.

Run the complete model tests with:

```sh
./gradlew :analyzer:test
```

The tests include the required closed single-slot ring, fully observed spare-capacity
negative, insufficient persistence, missing-prefix, expired-lease and unsupported
deployment paths. Temporal subset selection verifies that a newer upstream dependent
does not reset an older persistent cycle. `TruthClosureOracleTest` additionally covers
upstream dependents, disjoint closed components and a nonblocked slot owner.

## Deliberate omissions

This model does not ingest v2 LogRecords, certify sequence prefixes, construct causal
cuts, track transitions, validate manifests, communicate over OTLP, operate a gRPC gate
or establish S1/S2/L1. It takes an already reconstructed complete snapshot and already
bounded persistence intervals as input. Those components remain blocked by the recorded
task prerequisites and contract review. Passing these tests therefore demonstrates
model behavior only; it is not detector correctness or integration evidence.
