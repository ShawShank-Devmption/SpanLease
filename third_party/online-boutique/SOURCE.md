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
