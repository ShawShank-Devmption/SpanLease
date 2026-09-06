# Synthetic observation scenario schema (frozen — contract-tagged PR to change)

Scenario files (`*.yaml` in this directory) are the analyzer's testbed until phase-3
integration (task C1.1), and later the shared format for golden replay tests. The
generator turns a scenario into a stream of `SpanLeaseEvent`s, applies the declared
delivery faults deterministically under `seed`, feeds the analyzer, and asserts `expect`.

Design rule this encodes: the analyzer's input is **observations, never system state** —
so a scenario declares (a) the true event history and (b) how delivery distorts it,
separately.

## Top-level structure

```yaml
name: string                      # unique; used in test names
seed: int                         # drives every random fault below
params:                           # optional analyzer-param overrides (defaults: design.md §4.1)
  tau_ms: 2000
  delta_ms: 1000
  epsilon_ms: 50
instances: [svcA-1, svcB-1, ...]  # instance_ids; declared before use
resources:                        # declared capacity per resource type (A5)
  - {id: "svcA-1:exec-main", capacity: 1}
events:                           # TRUE history, in true order per instance
  - {at_ms: 0,  instance: driver-1, type: rpc.invocation.sent, invocation: I1,
     caller_execution: "", target: svcA-1}
  - {at_ms: 5,  instance: svcA-1,  type: rpc.invocation.arrived, invocation: I1}
  # ... field names mirror design.md §4 (invocation, execution, resource,
  #     resource_instance, outcome, cancel_source, lease_expiry_ms, start_ref)
  - {at_ms: 600, instance: svcA-1, type: execution.lease, execution: "svcA-1:2",
     start_ref: "svcA-1:2", observed_age_ms: 600, lease_expiry_ms: 2100,
     repeat: {every_ms: 500, until_ms: 5000}}   # repeat: lease renewals without spelling each out
faults:                           # optional; omitted = perfect delivery
  reorder_jitter_ms: 0            # uniform [0, jitter] delivery delay per event (seeded)
  drop:                           # drop specific events (matched by instance + local_seq
    - {instance: svcB-1, local_seq: 3}          #   OR instance + type + invocation)
  duplicate:
    - {instance: svcC-1, local_seq: 2, times: 2}
  delay:
    - {instance: svcA-1, local_seq: 6, by_ms: 3000}   # > delta => frontier gap (R12)
  skew_ms: {svcB-1: 40}           # added to svcB-1's wall_time as seen by analyzer
expect:
  verdict: CONFIRMED_DEADLOCK      # the verdict once the stream is fully ingested + tau elapsed
  core_executions: ["svcA-1:2", "svcB-1:2", "svcC-1:2"]   # optional, exact match
  core_units: ["svcA-1:exec-main:slot-0", ...]            # optional, exact match
  unaccounted_units: []            # optional; required for inconclusive scenarios
  frontier_gaps: []                # optional
```

## Semantics and conventions

- `local_seq` is implicit: events are listed in true per-instance order and numbered
  1..n by the generator (I1 holds by construction). Fault matchers may use the implied
  number.
- `execution` ids follow design.md §4: `{instance_id}:{acquire local_seq}`.
- `at_ms` is true wall time on that instance's clock before skew is applied; used for
  emission order across instances in the generator and for persistence arithmetic in the
  analyzer. Causal order still comes only from sent→arrived pairs (same `invocation`).
- The generator validates causal sanity of the *true* history (no arrival without a send,
  no event for an execution before its acquire) and rejects invalid scenarios — faults,
  not authoring errors, are how we express distortion.
- Every scenario must set `expect.verdict`. Inconclusive expectations must also name the
  gap (`unaccounted_units` / `frontier_gaps`) — mirroring the rule that inconclusive
  verdicts name the missing item.

## Required canned scenarios (task C1.1)

| File | Expect |
|---|---|
| `three_cycle_deadlock.yaml` | CONFIRMED_DEADLOCK, exact core |
| `spare_capacity_cycle.yaml` | CONFIRMED_NO_DEADLOCK |
| `saturation_only.yaml` | CONFIRMED_NO_DEADLOCK |
| `lost_acquire.yaml` | CANDIDATE_INCONCLUSIVE naming the unaccounted unit |
| `lost_release.yaml` | CANDIDATE_INCONCLUSIVE after lease expiry (R7) |
