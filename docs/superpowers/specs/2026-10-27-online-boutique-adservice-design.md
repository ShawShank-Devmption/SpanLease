# Online Boutique Ad Service monitor integration

Date: 2026-10-27. Status: scope superseded by 2026-10-29 final-demo design.
Owner: Dev A for the application/demo harness, using existing Dev B instrumentation
and Dev C analyzer interfaces without changing their contracts.

## Intended outcome and selected scope

The owner selected **Java Ad Service + controlled deadlock** rather than the complete
shopping website. The existing local monitor must run actual Online Boutique
`hipstershop.AdService/GetAds` calls, show returned ads in normal operation, identify
the exact workers in an injected circular wait, and retain evidence after the owner
kills the separate workload PID. This is an isolated application-integration demo;
E-REALISM and G1–G3 acceptance remain pending.

The frontend, checkout, cart and other non-Java services are outside this first
integration. No whole-store coverage or existing Boutique deadlock is claimed.

## Source and implementation choice

Use GoogleCloudPlatform/microservices-demo at commit
`38e7348eb289eb5b87c0c6e8cb19ced0449dc389`, inspected from the official repository.

- `protos/demo.proto`: SHA-256
  `b1f2fa42c0a9305211bf51fba7d8b5832cd3defd7b8855c327ebd5b1f0f86884`.
- `src/adservice/src/main/java/hipstershop/AdService.java`: SHA-256
  `1123688864c953494dd2625abcd8123c0ce7c7c5a233a1676e2d901f80b6be50`.

Recommended approach: retain the upstream protobuf contract and adapt Google's ad
catalog/selection logic into the existing Java 21 testbed. Preserve Apache-2.0
copyright/license attribution and document every adaptation. Use JDK collections
and constructor-injected RNG instead of adding Guava, Log4j or upstream's separate
build/runtime dependencies. Preserve category matches, duplicate context-key
behavior and two uniform fallback selections with replacement. This is explicitly
an **instrumented SpanLease adaptation**, not an unmodified upstream binary.

Alternatives considered: running the untouched upstream service would provide native
gRPC traffic but lacks the required gate/evidence and downstream waits; deploying
the whole store would add languages and dependencies outside the selected scope.

## Native application contract

Vendor the pinned `demo.proto` with its upstream copyright. Use its generated gRPC
stub and unchanged service/message names and field numbers:

```text
hipstershop.AdService/GetAds(AdRequest) -> AdResponse
AdRequest.context_keys = 1
AdResponse.ads = 1
Ad.redirect_url = 1; Ad.text = 2
```

No `Chain.Call` request travels on the Boutique route. A source/provenance note names
the pinned files, checksums, upstream URL and adapter changes. Existing repository
grpc/protobuf/Java pins apply; no new dependency is proposed.

## Modes and controlled fault

Deploy three Java Ad Service replicas, named `ads-a`, `ads-b`, `ads-c`, with separate
fixed application gates and callback executors, in one killable workload JVM.

| Mode | Behavior and expected evidence |
|---|---|
| Boutique normal | A real `GetAds` request with `kitchen` context returns the upstream kitchen ads. No downstream callback is enabled. Fully observed settled state can support a negative cut. |
| Boutique injected deadlock | Explicit diagnostic configuration enables `ads-a → ads-b → ads-c → ads-a`. Each gate has one slot. Three owners block and the final A callback queues; expect three executions, three units and one queue member after qualified persistence. |
| Boutique spare capacity | Same injected route, but A has two slots. The callback can run; bounded diagnostic leaf work makes its interval observable, then the request returns real ads. Expect no confirmed closed wait and successful completion. |

The callback behavior is an injected fault motivated by DDMon's nested-RPC pattern,
not part of stock Boutique or a reproduction of DDMon's Erlang monitor/proofs.
Finite leaf work is diagnostic behavior, not upstream business latency.

Use one documented application-only metadata key,
`x-spanlease-boutique-hops`, as the bounded diagnostic hop budget. A root starts at
3; each downstream call decrements it, and 0 is a local leaf. It is accepted only
when the diagnostic mode is configured before admission opens. Reject malformed,
duplicate, negative or greater-than-3 values before job submission. Peers come from
the immutable local deployment configuration, never an address in a request.
An absent header always means local ad handling; it cannot enable forwarding. A
supplied diagnostic header in normal mode is rejected rather than silently enabled.
Normal business context keys and all detector attributes retain their meanings.

Each forwarding attempt uses the existing blocking helper, fresh invocation ID and
exact request/response causal references. The real application RPC carries the
diagnostic metadata; no extra hidden coordination/barrier participates in the wait.
Archive the fault route/budget/mode and seed as a separate application configuration,
never as truth labels in the detector manifest or observations.

## Runtime and monitor integration

Extend the existing scenario selection with an explicit application kind so the
launcher, renderer and data-source label cannot confuse Boutique and Chain. Keep
the existing Chain scenarios and CLI working. Reuse `RunPlan` for fresh epochs,
declared pool membership and timing context; no detector contract extension is
needed. Reuse the existing gate, server/client hooks, scanner, exporter and driver
recorder rather than implementing another telemetry pipeline.

Show **Online Boutique Ad Service · instrumented adaptation**, the native RPC method,
actual replica names, ad response text/URLs, the workload PID, slot/execution IDs and
the existing OS/JVM/thread diagnostics. Derive names from the selected deployment;
remove Chain-specific assumptions from these shared display paths.

The injected interactive deadlock has no automatic cancellation/deadline, matching
the approved manual-stop monitor behavior. Tests use finite cleanup bounds. Normal
and spare-capacity controls use finite RPC deadlines. Allow the analyzer to receive
final checkpoints and report settled evidence before closing a naturally completed
run; a fast ad response alone is not a negative detector verdict.

The owner kills only the displayed workload PID. The monitor and analyzer remain
separate; actual exit ends the running incident and preserves the historical
confirmation. A crash never fabricates release events or a confirmed negative.
The existing loopback control token, same-origin restrictions, bounded display
history and manifest clock-qualification limits continue to apply.

## Invariants and boundaries

Preserve I1–I5: serialized transitions/publication, acquire-before-run,
actual-exit-before-release, no application-path telemetry I/O waits, and causal
references before transport. Cancellation does not release an occupied slot.
Only the native unary callback submits the admitted job; transport callbacks never
become modeled executions. Disable retries/hedging. Diagnostics stay outside
analyzer inputs and remain timestamped samples, not certified simultaneous state.

`common`, detector DTOs/schema version and analyzer semantics are unchanged. Changes
belong in testbed, the vendored application protobuf/source attribution, monitor
resources and documentation. This is not a new event/report/replay contract.

## Acceptance and verification

1. Native-wire regression calls the generated Boutique blocking stub and verifies
   exact `kitchen` URLs and ad text; empty/unknown contexts return two catalog ads.
2. Adapter tests verify diagnostic-header validation and that invalid requests never
   acquire a worker. Gate/RPC lifecycle regressions cover touched R1/R2/R3/R14/R16
   responsibilities without reusing production reduction as an oracle.
3. Real gRPC → custom OTel logs → OTLP → separate analyzer confirms the injected
   wait with the exact three owners/units and queued A callback. It remains active
   beyond the former automatic stop and ends on workload PID termination.
4. The spare-capacity mode returns ads and has a fully observed negative report
   during its diagnostic leaf interval. Normal mode completes without a false
   deadlock confirmation, and observed final state is not inferred from completion.
5. Browser verification checks application provenance/method, actual reply data,
   worker localization, sticky historical evidence and automatic stopped status.
6. Run formatting, `./gradlew build`, affected analyzer/testbed checks and JavaScript
   syntax validation. Keep the existing Chain regressions green.

Record actual commands/results, source revision, seed and material limits in the
handoff. These demo checks do not accept I-LITMUS/I-CI, independent A-TRUTH, E-REALISM,
soundness, latency guarantees or G1–G3.
