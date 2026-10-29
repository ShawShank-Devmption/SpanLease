# Online Boutique source attribution

Source: https://github.com/GoogleCloudPlatform/microservices-demo

Pinned commit: `38e7348eb289eb5b87c0c6e8cb19ced0449dc389`.

The application protocol at `testbed/src/main/proto/onlineboutique/demo.proto` is a
byte-for-byte copy of upstream `protos/demo.proto`, SHA-256
`b1f2fa42c0a9305211bf51fba7d8b5832cd3defd7b8855c327ebd5b1f0f86884`.
The generated classes keep the native `hipstershop.AdService/GetAds` RPC and fields.

`BoutiqueAds` adapts the catalog and selection algorithm from
`src/adservice/src/main/java/hipstershop/AdService.java`, SHA-256
`1123688864c953494dd2625abcd8123c0ce7c7c5a233a1676e2d901f80b6be50`.
Copyright 2018 Google LLC; distributed under the accompanying Apache-2.0 LICENSE.
Changes: JDK collections replace Guava; RNG is constructor-injected; the existing
SpanLease adapter replaces upstream server setup/logging/health/tracing scaffolding.
Category ordering, matching, repeated keys and uniform fallback with replacement
are preserved. This is an instrumented adaptation, not an unmodified upstream binary.

The bounded worker gate, callback fault, diagnostic leaf work, telemetry, root driver
and monitor are SpanLease additions. Stock Boutique does not have this callback cycle.

## Local full-store demo

`deploy/online-boutique/compose.yaml` adapts the Apache-2.0
[release manifest](https://github.com/GoogleCloudPlatform/microservices-demo/blob/38e7348eb289eb5b87c0c6e8cb19ced0449dc389/release/kubernetes-manifests.yaml)
from the same pinned commit. The upstream adservice and shoppingassistantservice are
omitted; the store's Ad Service address is `host.docker.internal:9555`, the host's
driver-role BoutiqueEdge. The Java Ad Service replicas remain an instrumented
adaptation, and their callback cycle is explicitly injected.

### Image inventory checked 2026-10-29

`docker manifest inspect --verbose IMAGE:v0.10.7` and
`docker buildx imagetools inspect IMAGE:v0.10.7` confirmed every Boutique image below
has a single Linux amd64 manifest and no arm64 variant. Each therefore specifies
`platform: linux/amd64`; Apple Silicon runs these images under Docker emulation.
All Boutique repositories use prefix
`us-central1-docker.pkg.dev/online-boutique-ci/microservices-demo/` and tag `v0.10.7`.

| Image | Immutable manifest digest |
|---|---|
| frontend | `sha256:e1cbd4561d3c9d27497dc0e8b1c12da194c4bf2ea82648f58bf38f073698f483` |
| productcatalogservice | `sha256:ba573405e9b03ea29970185eeaf0a9de9db7400b9104695e39d4dfc0fb651cb5` |
| currencyservice | `sha256:c581681bbf9abec8563e520aac3f3dbe3e7498853b6eb263193bc80c87e42f74` |
| cartservice | `sha256:e5310c2aa45732e351c55c88051700d958dc7ed2693e66405fc6c70cf080109e` |
| recommendationservice | `sha256:69291992f5511e9199d08c24a3ceb6470b39461933304fbdad1c7ddfc7358fd9` |
| shippingservice | `sha256:3984397422ef1b45bd8a7c6736f935f4b6a41a1724ea39f9563b26733ed633e4` |
| checkoutservice | `sha256:f0e72ff7b438dd2e8776fb142d5132cfa5ba02c523466f1491270c576412d33f` |
| paymentservice | `sha256:e1ff27c553f7e0c31f4a10f1950ec0ee34cd41d77dd819b1fcfa139273891602` |
| emailservice | `sha256:1142813191b50f7e74bd1d8957d3b272496e9648b745d2c9914752b94356eade` |
| loadgenerator | `sha256:f86f07c0da48bc00e7843e060b6811b56b1ad8a184d3962f5e80e15f42370cc8` |
| redis:alpine (multi-platform index, includes Linux arm64/v8) | `sha256:47b08295737773de3b711fb86f13ae802f5074e96a9a2d29f5f80164ac08c046` |

### Kubernetes-to-Compose transformations

- Use container listener ports directly: catalog 3550, currency 7000, cart 7070,
  recommendation 8080, shipping 50051, checkout 5050, payment 50051 and email 8080.
  In particular checkout uses `emailservice:8080`, replacing the Kubernetes Service
  port 5000. Frontend and loadgenerator use frontend port 8080, replacing Service 80.
- Only frontend is published, on `127.0.0.1:8080:8080`. Internal services use the
  Compose network. Redis is ephemeral, matching the upstream emptyDir intent.
- Preserve frontend's required `SHOPPING_ASSISTANT_SERVICE_ADDR` setting even though
  that optional service is omitted. Preserve `ENABLE_PROFILER=0` and the upstream
  services' `DISABLE_PROFILER=1` settings.
- Frontend resolves `host.docker.internal` with `host-gateway`; the Java edge defaults
  to `127.0.0.1:9555`. If the Docker backend cannot reach host loopback, run the
  monitor with `--edge-bind 0.0.0.0:9555`; this exposes that edge port to the LAN.
  The monitor remains loopback-only.
- Loadgenerator uses `USERS=3`, `RATE=1` for short demos (guidance: at most 30 minutes).
  Analyzer history is retained for the run; clock qualification expires after 60 minutes.
- Kubernetes probes, ServiceAccounts, scheduling/resource requests and the loadgenerator's
  frontend-readiness init container are omitted. Compose dependencies provide startup order;
  use the actual HTTP response and service logs to verify readiness.

### Run commands

Start `./gradlew :testbed:monitorDemo`, select/start a Boutique run through the
monitor controls, then run `docker compose -f deploy/online-boutique/compose.yaml up -d`.
The store is at `http://localhost:8080/product/0PUK6V6EV0`.
Stop the displayed workload PID between runs. Stop the Docker store with
`docker compose -f deploy/online-boutique/compose.yaml down`.

The final source selector and injection button are Dev 2's T8 handoff; before that
UI lands, authenticated `/api/run?scenario=boutique-live` and `/api/inject` use the
existing monitor control token.

### Local verification — 2026-10-29

Verified on this Apple Silicon Mac (macOS 27.2, Java 21.0.12.1, Docker Engine
29.9.2 / Compose v5.6.1). The default `127.0.0.1:9555` edge was reachable from
Docker Desktop; no all-interface bind override was needed. All eleven containers
were running during the walkthrough.

| Run | Observed evidence |
|---|---|
| `boutique-live-b07e9ea3` | Chrome displayed the Candle Holder product and its upstream ad; loadgenerator traffic reached the edge (17 forwarded / 17 OK at the recorded sample); a confirmed negative was received. |
| `boutique-live-3a525a02` | Frontend reconnect after the specified 30-second workload outage: approximately 17.6 s from the new run request and 17.1 s from its first edge sample to the first forwarded-call sample. |
| `boutique-deadlock-e33254ec` | Monitor displayed DEADLOCK DETECTED with ads-a/b/c: exactly 3 executions, 3 slot-0 units and 1 ads-a queue entry; first certified persistence 2124 ms ≥ 2000 ms. Product HTTP response stayed 200 with an empty ad section. After PID termination, STOPPED and `first-deadlock.json` retained the evidence. |
| `boutique-spare-capacity-f21a43e9` | Injection returned the two kitchen ads; 12 confirmed-negative reports had certified horizons strictly between injection and completion. No first deadlock; frontend ad returned and successful store calls rose from 5 to 13. |

Because measured reconnect exceeded 15 s, **restart frontend after starting each
new Boutique run**:

```bash
docker compose -f deploy/online-boutique/compose.yaml restart frontend
```

Run artifacts are under `testbed/build/monitor-runs/<run-id>/`: configuration,
manifest, bounded retained reports, monitor result and first-deadlock report where
applicable. These local build artifacts are gitignored and survive until that build
directory is cleaned. The full Dev 2 UI and joint T11 / 15-minute rehearsal remain
pending. These checks do not establish G1–G3 acceptance or research guarantees.

### Integrated follow-up

The later `feat/final-demo-integrated` branch includes the Dev 2 UI and litmus suite.
Its clean-clone build, real Docker walkthrough and 15-minute live-store cadence
check passed; full visual qualification and independent sign-off remain pending.
See [integrated verification](../../docs/final-demo-verification.md) for exact
results and run IDs. This supersedes the earlier lane-local pending inventory,
without changing the source provenance or research acceptance limits above.
