# 30-day MVP report

This document is the active MVP cutline for Token Pilot 0.1.0. It describes
verified repository behavior, not a promise that every artifact is already
published.

## Current release-candidate scope

| Area | Status |
| --- | --- |
| Usage normalization and token breakdown invariants | Implemented and unit tested |
| Exact monetary arithmetic and currency validation | Implemented with `BigDecimal` and explicit currency |
| Missing-pricing policy and immutable pricing snapshot | Implemented |
| Versioned model catalog and context admission | Implemented |
| UTF-8 byte heuristic estimator | Implemented; `TEXT_ONLY`, heuristic, not exact BPE |
| Atomic in-memory budget reservation | Implemented |
| Estimate/actual reconciliation | Implemented, including pending liability and duplicate-safe callbacks |
| Threshold notification events | Implemented as process-local best-effort delivery |
| Token Pilot-owned Micrometer metrics | Implemented with bounded default tags |
| Spring AI 2.0 non-streaming lifecycle | Implemented and fake-provider E2E tested |
| Core-only and Starter publication paths | Build and generated consumer gates present; final staging evidence required |
| Real-provider compatibility | Not part of the verified claim |
| Durable stores/outbox/replay | Post-MVP |
| Advanced streaming reconciliation | Post-MVP |

## Release exit criteria

- `./gradlew test` passes.
- Publication metadata, MIT license metadata, Java 25 bytecode, and dependency boundaries pass verification.
- `token-pilot-core` resolves from an external consumer without Spring, Spring AI, Micrometer, or Reactor runtime dependencies.
- `token-pilot-starter` resolves with a provider selected by the application and does not bundle a provider implementation.
- Core and Starter use the same release version.
- The default Token Pilot metrics do not expose raw user, tenant, request, reservation, or model identifiers.
- The sample app can expose health, smoke, deterministic ledger, and Prometheus checks without an API key.
- Release documentation does not describe staging artifacts as a Maven Central release.

## Explicit cutline

The following are deliberately outside the 0.1.0 MVP:

- Exact provider BPE/tokenizer assets.
- Full media, tools, schemas, and structured-output admission.
- Streaming chunk accounting and cancellation reconciliation.
- Durable reservation/accounting/notification delivery.
- Provider routing, retries, fallback, circuit breakers, and gateway HTTP runtime.
- Multi-tenant administration and durable operational stores.
