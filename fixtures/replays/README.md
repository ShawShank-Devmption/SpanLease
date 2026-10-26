# Version 2 replay fixtures

These JSON sessions were captured from local real gRPC→SDK/OTLP runs on 2026-10-26,
seed 42, and truncated at a qualifying evaluation. They contain the complete manifest,
ordered receipt envelopes and every evaluation through that point. The `*-expected`
files are the exact reports at the final evaluation. Re-evaluation requires no clock,
network, testbed truth or random identity source.

These are explicitly new v2 fixtures, not silent reinterpretations of the historical
v1 YAML under `analyzer/src/test/resources/replays`. The replay container has its own
format version 1; enclosed observations, manifest and reports retain schema version 2.
Do not conflate those version domains. Generator workloads: Workload.closed-ring
(A→B→A, k=1) and Workload.spare-capacity (A→B→A, A k=2, finite leaf work).
