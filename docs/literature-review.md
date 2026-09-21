# SpanLease literature review and publication assessment

> The revised requirements/design/task plan now specifies a v2 evidence protocol and
> explicit application-gate deployment in response to this review (decisions D8–D10).
> This is a proposed implementation baseline awaiting G0 approval and validation, not
> measured proof that the identified research opportunity has been achieved.

Review date: 2026-09-19. Conclusion: **a plausible systems research direction, but
novelty is not yet established and this repository is not ready for a full conference
paper**. The most promising contribution is a verified evidence protocol for
pre-deadline RPC/executor circular waits under incomplete observation, supported by
real Java/gRPC instrumentation and a fair empirical comparison.

Java, gRPC and OpenTelemetry integration alone are unlikely to carry a strong research
paper. Neither wait-for graphs, multi-instance reduction, consistent cuts, leases,
live spans, nor inconclusive verdicts are new individually. The proposal already
acknowledges much of this; its correctness and baseline details still need revision.

## Review method and limits

Read the local proposal's related work and references, then retrieved public primary
sources: DDMon's arXiv technical report (including §9 and Appendix B), USENIX pages for
Hindsight and CRISP, and arXiv metadata/abstracts for recent adjacent papers. Searched
arXiv for distributed deadlock detection and microservice tracing/diagnosis, including
2024–2026 results. Queries included `ti:deadlock AND (all:distributed OR
all:microservice OR all:RPC)` and `all:microservices AND all:tracing`. Irrelevant routing,
robotics and machine-learning “deadlock” results were excluded. Publication dates must
be checked per record; API search filters alone are not evidence of the year.

This is a focused review, **not an exhaustive systematic review or proof of absence of
prior art**. The 2024–2026 adjacent entries below were screened at abstract level;
unverified venue status is not promoted to peer-reviewed publication. Before submission,
expand to ACM DL/IEEE Xplore/DBLP, backward and forward citations of DDMon, and the
thread-pool/middleware literature. Record search dates, exact queries, inclusion
decisions and source versions in the artifact. No “first system” claim is justified yet.

## Closest work and recent adjacent research

| Work / evidence inspected | Relevant result | Overlap and implication for SpanLease |
|---|---|---|
| **Rowicki, Francalanza, Scalas. Correct Black-Box Monitors for Distributed Deadlock Detection: Formalisation and Implementation. OOPSLA 2025, PACMPL 9(OOPSLA2), article 291.** [DOI](https://doi.org/10.1145/3763069), [technical report v3](https://arxiv.org/html/2508.14851v3). Relevant report sections inspected. | Distributed proxy monitors observe RPC messages and exchange probes; formal transparency/preciseness results, Coq mechanization, Erlang/OTP DDMon implementation and overhead evaluation. | Closest direct competitor. RPC-level detection/localization and correct monitoring are already established topics. SpanLease must defend executor admission, actual worker ownership, delayed one-way telemetry and explicit uncertainty. A port cannot inherit the original proof. |
| **Zhang, Xie, Anand, Vigfusson, Mace. The Benefit of Hindsight: Tracing Edge-Cases in Distributed Systems. NSDI 2023, pp. 321–339.** [Publisher page](https://www.usenix.org/conference/nsdi23/presentation/zhang-lei). Abstract and bibliographic record inspected. | Always-on local capture with retroactive collection when symptoms trigger; evaluates overhead and rare-problem capture. | Cheap capture plus triggered retrieval is prior art. Compare trigger-to-evidence delay, bytes and buffer eviction. Hindsight is a collection system, not itself a capacity-closure deadlock detector; a timeout trigger would unfairly handicap the comparison. |
| **Zhang, Ramanathan, Raj, Parwal, Sherwood, Chabbi. CRISP: Critical Path Analysis of Large-Scale Microservice Architectures. USENIX ATC 2022, pp. 655–672.** [Publisher page](https://www.usenix.org/conference/atc22/presentation/zhang-zhizhou). Abstract and bibliography inspected. | Top-down/bottom-up critical-path analysis and online anomaly detection, evaluated in Uber's large microservice system. | Request dependency localization is established. Explain why latency critical paths do not certify closed executor-capacity waits; do not conflate anomaly ranking with predicate verification. Useful evidence for realistic scale and deployment expectations. |
| **Phan et al. CrossTrace: Efficient Cross-Thread and Cross-Service Span Correlation in Distributed Tracing for Microservices. 2025 preprint.** [arXiv:2508.11342](https://arxiv.org/abs/2508.11342). Metadata/abstract inspected; venue not verified. | Infers intra-service span relationships from delay patterns and uses eBPF for cross-service correlation. | Correlation with reduced instrumentation is active research. Distinguish observed invocation identity from inferred relationships; approximate attribution cannot silently satisfy A2. Useful alternative instrumentation discussion, not a drop-in correctness baseline. |
| **Anand, Stolet, Mace, Kaufmann. Generating representative macrobenchmark microservice systems from distributed traces with Palette. 2025 preprint.** [arXiv:2506.06448](https://arxiv.org/abs/2506.06448). Metadata/abstract inspected; venue not verified. | Generates representative systems from trace-derived topology, branching, call ordering and execution-time models. | Directly motivates an evaluation beyond the three-service chain. Use representative supported synchronous subgraphs or a similar methodology; topology alone does not supply slot-level deadlock ground truth. |
| **Du, Shi, Chen, Li, Guo. A Microservice Graph Generator with Production Characteristics. 2024 preprint.** [arXiv:2412.19083](https://arxiv.org/abs/2412.19083). Metadata/abstract inspected; venue not verified. | Produces smaller dependency graphs retaining production call-graph characteristics. | Another route to external-validity workloads; preserve repeated calls and service sharing. Such graphs do not independently establish ownership or deadlock onset. |
| **Gu, Liu, Ke. A Flow Extension to Coroutine Types for Deadlock Detection in Go. 2026 preprint.** [arXiv:2602.19686](https://arxiv.org/abs/2602.19686). Metadata/abstract inspected; venue not verified. | Type-based analysis of coroutine/channel patterns with solver support. | Recent deadlock work, but a different problem: source/type analysis of Go channels, not online cross-service Java executor evidence. Discuss as prevention/static analysis; do not add an irrelevant runtime baseline merely because it is recent. |

### DDMon changes the positioning most

The report's §9 explicitly treats **observing monitors** receiving delayed notifications
as future work and reports counterexamples to naive reuse of proxy algorithms. It also
identifies **RPC timeouts** as future work requiring care about monitor visibility and
the definition of deadlock. These are concrete opportunities for SpanLease, provided
its observation/completeness and cancellation semantics are actually proved.

Appendix B explicitly models replication using a coordinator and workers. Therefore
the proposal's simple “DDMon: no multi-instance capacity” table entry is too categorical.
Investigate whether executor admission can be encoded using that model and where a
shared queue choosing any available worker changes the evidence or monitor. A measured
advantage or an expressiveness argument is needed; counting Java slots is not enough.

The proposal separately attributes a passive observer to an associated thesis. This
review did not verify that thesis. Do not present that attribution as an established
OOPSLA implementation or describe an unverified passive variant as the published tool.

## Foundational work still required

The proposal cites Coffman–Elphick–Shoshani (1971), Chandy–Misra–Haas (1983), Knapp
(1987), Cooper–Marzullo (1991), and Garg–Waldecker (1994). These establish the ancestry
of deadlock conditions, distributed detection, AND/OR models, consistent cuts and
global-predicate detection. Cite them for those foundations, not SpanLease novelty.
Their exact editions/pages and any stronger attributed theorem should be checked from
primary texts before manuscript use; this review did not re-verify every classic paper.

Highest-priority unresolved citations from the proposal:

- Cheriton–Skeen, *Understanding the Limitations of Causally and Totally Ordered
  Communication* (SOSP 1993): verify the specific RPC monitoring algorithm attribution.
  Until then describe the implemented baseline as a generic invocation wait-for monitor.
- Kaveh–Emmerich, *Deadlock Detection in Distributed Object Systems* (ESEC/FSE 2001):
  retrieve full text and compare resource/activation/threading models directly.
- Sethi–Anand, *On Concurrency Improvements in Enterprise SOA Middleware* (2008), and
  patent US8060878B2: verify both bibliographic details and bounded-thread-pool content.
  Industrial prior art matters even though a patent is not a peer-reviewed paper.
- X-Ray in-progress segments, Logfire pending spans, OTel zpages, partial flushing:
  pin product versions and verify exact behavior. Export of finished children in an
  unfinished trace is not necessarily export of the running parent span.

## Defensible research claim

Suggested claim to develop and test:

> SpanLease investigates how much live evidence is sufficient to certify persistent
> RPC/executor circular waits before deadlines in bounded Java/gRPC deployments. It
> combines pre-handler queue and slot observations with causally closed evidence
> windows, and reports why incomplete evidence prevents a verdict.

Avoid a priority claim until the broader search is complete. Avoid claiming S1/L1 until
proved. The useful theoretical contribution could be a sufficient evidence protocol
and its correctness argument, including a counterexample showing why ordinary live
spans or unsynchronized notifications alone are insufficient. Demonstrating that two
different histories yield identical observations is useful justification for an
inconclusive verdict; it is not a new impossibility theorem without a formal model.

## Changes that most improve the paper

1. **Close the correctness gap first.** Resolve completeness/initial-state/response
   causality and interval semantics. Prove reduction termination/order independence
   and S1 for the actual observation protocol. Model-check small histories with
   k=1,2, multiple cycles, missing suffixes, cancellation and response races. Formal
   proof and bounded checking play complementary roles; checking is not a full proof.
2. **Resolve the integration risk before building the analyzer.** Demonstrate early
   queue capture, one execution per modeled handler, actual slot release after exit,
   and blocking boundaries on real gRPC. A synthetic YAML stream cannot validate this.
3. **Make the strongest comparison fair.** Compare ordinary live spans and live spans
   enriched with identical queue/slot facts through the same analyzer. Separately
   compare an invocation monitor and a carefully specified DDMon-inspired adaptation.
   Give all baselines identical workloads, deadlines, timing origin and truth labels.
   Publish baseline limitations without presenting adaptations as original systems.
4. **Add realistic evidence.** Keep deterministic litmus tests, but include a supported
   application or trace-derived topology, varying k, replicas, cycle length, unrelated
   load and deadlines. Report the adaptations needed to enforce blocking-unary scope.
5. **Test explanations and overhead.** Measure exact unit/execution localization,
   missing-evidence classification, both kinds of false confirmation, queue/telemetry
   overhead under degradation, and useful time remaining until the earliest relevant
   deadline. Retain original baseline outcomes even when they challenge the hypothesis.
6. **Treat ablations as experiments.** Removing leases may not cause a false positive
   when intact completeness guards already prevent it. A valid outcome is increased
   stale-candidate retention or no change in safety. Do not disable other safeguards
   merely to manufacture an expected failure.

For false-confirmation statistics, use independent scenario runs (or account for
within-run dependence), not thousands of correlated evaluation ticks as independent
trials. With zero failures in N independent Bernoulli trials, the exact one-sided
95% upper bound is `1 - 0.05^(1/N)` (approximately `3/N`). Roughly 2,995 independent
zero-failure trials are needed for an upper bound below 0.1%; that statement remains
conditional on the sampled workload distribution and is not a soundness proof.

## Venue and submission gate

**Middleware** is the best thematic target if the paper delivers a validated middleware
mechanism, a credible correctness argument and strong live-observability comparisons.
**ICPE** fits if the main result is the accuracy/latency/overhead tradeoff with rigorous
experiments. **IC2E** fits deployment and cloud-engineering evidence. These are fit
assessments, not acceptance predictions; current calls, tracks and deadlines were not
verified. A workshop, short paper or demonstration is more realistic for preliminary
results than a full research paper with unimplemented claims.

Do not submit a full paper until: the feasibility gate passes; proposal/contract defects
are resolved; both litmus tests and adversarial counterexamples pass; S1 is proved or
claims are explicitly narrowed; L1 and timing are justified; strong baselines and an
external-validity workload are complete; raw results/CIs and reproduction scripts are
available; and every citation and comparison-table cell has an evidence source.

## Implementation sources checked

- [grpc-java v1.68.1 ServerImpl](https://github.com/grpc/grpc-java/blob/v1.68.1/core/src/main/java/io/grpc/internal/ServerImpl.java): method lookup and call handling are scheduled on the executor before ordinary interception.
- [grpc-java v1.68.1 ServerStreamTracer](https://github.com/grpc/grpc-java/blob/v1.68.1/api/src/main/java/io/grpc/ServerStreamTracer.java): factory receives headers before stream creation; feasibility candidate, not a complete slot adapter.
- [OTLP specification](https://opentelemetry.io/docs/specs/otlp/): populated partial-success responses must not be retried; transient failure can use retryable status. The corrected receiver behavior is in revised design §6.3.
