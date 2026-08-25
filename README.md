<img width="300" height="300" alt="fe73d88a-b0ec-417e-80ef-cd57a9837df6" src="https://github.com/user-attachments/assets/370c7583-8c62-41b7-8dde-a700b5e56dd4" />

# TokenPilot

> One Java gateway to control LLM providers, routing, fallback, tokens, and costs.

TokenPilot helps Java applications control an LLM call before it is sent and
reconcile its cost after it completes. It brings token estimation, context
checks, budget enforcement, and actual usage-based accounting into one layer.

## Why TokenPilot?

Adding an LLM to a service is more than calling a provider API. Applications
need to know whether a request fits the model context window, whether it can
stay within a budget, and what it actually cost after the provider responds.

Without a common layer, each service rebuilds token counting, pricing, budget,
and fallback rules in its own way. TokenPilot provides a single place to apply
those controls.

## What It Controls

- Estimates tokens and checks context limits before an LLM call.
- Enforces spending limits before a provider is invoked.
- Reconciles estimated cost with provider-reported usage after a call.
- Records usage, cost, policy decisions, and budget lifecycle events.
- Integrates with Spring AI while keeping LLM control and accounting concerns
  separate from application business logic.

## How It Works

```text
LLM request
→ token and context preflight
→ cost and budget decision
→ provider call
→ actual usage normalization
→ final cost reconciliation
```

## Gateway Direction

TokenPilot starts with LLM usage control and cost accounting. It is evolving
into a Java LLM Gateway for unified provider access, model routing, reliable
fallback, and operational policy control.

Spring AI is the first integration path. The long-term goal is to let Java
applications use the same control layer regardless of the provider or client
library behind it.

## Project Status

TokenPilot 0.1.0 is under active development. The current foundation includes
model cost calculation, owner-specific metrics, atomic budget reservation and
notification, and Spring Boot autoconfiguration. For supported non-streaming
Spring AI `ChatClient` calls, TokenPilot now performs conservative preflight,
claims one provider dispatch, and reconciles provider-reported actual usage
against the reservation-time pricing snapshot.

## Documentation

- [10-minute quickstart](docs/QUICKSTART.md) — choose Core or Starter and run the first verification path.
- [Configuration reference](docs/CONFIGURATION.md) — properties, defaults, conditions, and failure modes.
- [Metrics reference](docs/METRICS.md) — Token Pilot-owned meters and legacy compatibility policy.
- [Sample app runbook](docs/SAMPLE_RUNBOOK.md) — local app, Prometheus, Grafana, and troubleshooting commands.
- [Release procedure](docs/RELEASE.md) — staging, signing, external consumer, and Central Portal gates.
- [30-day MVP cutline](docs/30_DAY_MVP_REPORT.md) and [post-MVP evolution plan](docs/EVOLUTION_PLAN.md).

## Micrometer metrics

When a `MeterRegistry` is available, the Spring Boot starter publishes
TokenPilot-owned, low-cardinality metrics for control and accounting outcomes:

| Metric | Tags | Meaning |
| --- | --- | --- |
| `tokenpilot.cost.total` | `currency` | Newly committed actual cost from usage-based reconciliation |
| `tokenpilot.preflight.requests` | `decision`, `reason` | Context admission decisions |
| `tokenpilot.budget.reservations` | `state` | Atomic reservation results |
| `tokenpilot.reconciliation.error.tokens` | `direction` | Absolute estimate/actual token error |
| `tokenpilot.reconciliation.outcomes` | `outcome`, `reason` | Applied reconciliation outcomes |
| `tokenpilot.pricing.missing` | `policy` | Missing pricing observed at the provider boundary |
| `tokenpilot.listener.failures` | `listener`, `phase` | Isolated accounting-listener failures |
| `tokenpilot.notification.events` | `outcome`, `threshold` | Notification delivery and deduplication outcomes |

The default user-tag whitelist is empty. The metrics above never include raw
model, tenant, user, request, reservation, or idempotency identifiers. Their
tag values come from bounded domain enums or registered currency codes.

```yaml
token-pilot:
  metrics:
    enabled: true
    tag-whitelist: []
    legacy-ai-token-metrics-enabled: false
```

The former `ai.token.*` meters are disabled by default because Spring AI
Observability may already publish standard token telemetry. Set
`token-pilot.metrics.legacy-ai-token-metrics-enabled=true` to opt in during
migration. This is a 0.1.x compatibility bridge, including the legacy raw
`model` tag, and is planned for removal in 0.2.0. Migrate dashboards to Spring
AI token telemetry and the `tokenpilot.*` control/accounting meters before
then. `tag-whitelist` applies only to that legacy path and limits keys, not the
cardinality of application-provided values. Existing direct
`MicroCostMetricsPublisher` constructors retain their legacy `tenant_id`
allowlist behavior; the starter default remains empty.

Accounting metrics consume newly applied reservation transitions, so reused
callbacks do not add cost twice and unavailable actual usage is recorded as
`reconciliation_required`, not as zero cost or zero error. Listener delivery
is synchronous, best-effort, and at-most-once without a durable outbox.
Micrometer counters use `double` internally and are operational telemetry, not
the monetary source of truth; the ledger's `BigDecimal` values remain
authoritative. The legacy cost-only commit methods cannot carry token/model
correlation and do not emit these accounting metrics; new reservations should
use the usage-based reconciliation API.

`LedgerListener` and other optional observer `RuntimeException`s are isolated:
they do not change ledger/provider results and later listeners still run.
JVM `Error`s are not swallowed.

For direct autoconfiguration composition, use
`TokenPilotBudgetPolicyFactory.from(properties)` instead of the former
`TokenPilotProperties.toBudgetPolicy()`. Keeping the budget return type out of
the shared properties class allows autoconfiguration to start when the optional
budget module is absent.

## Spring AI starter

Spring Boot applications use one Token Pilot convenience starter and select
their Spring AI provider separately. For example:

```gradle
dependencies {
    implementation 'cloud.token-pilot:token-pilot-starter:<version>'
    implementation 'org.springframework.ai:spring-ai-starter-model-openai:2.0.0'
}
```

Token Pilot does not choose or bundle a provider. The published adapter and
starter are compile/runtime verified from their generated Maven and Gradle
metadata against the supported Java 25, Spring Boot 4.1.0, and Spring AI 2.0.0
baseline.

## Framework-independent core

Applications that do not use Spring can depend on `token-pilot-core` alone:

```gradle
dependencies {
    implementation 'cloud.token-pilot:token-pilot-core:<version>'
}
```

```java
import io.tokenpilot.core.CoreComponents;
import io.tokenpilot.core.TokenBudget;
import io.tokenpilot.core.TokenEstimator;
import io.tokenpilot.core.domain.BudgetResult;
import io.tokenpilot.core.domain.TokenCountResult;

TokenEstimator estimator = CoreComponents.utf8ByteHeuristicTokenEstimator();
TokenBudget budget = CoreComponents.tokenBudget(CoreComponents.defaultModelRegistry());
TokenCountResult input = estimator.estimate("hello");
BudgetResult result = budget.check("gpt-4o-mini", input, 0);

System.out.println(result.canonicalModelId().orElseThrow());
System.out.println(result.estimatorDescriptor());
System.out.println(result.tokenizationBasis());
System.out.println(result.reason()); // INCOMPLETE_SCOPE for TEXT_ONLY
```

The UTF-8 estimator is intentionally `TEXT_ONLY` and heuristic. It can report
`INDETERMINATE` for a short text input; a safe upper bound that exceeds the
model context is reported as `EXCEEDS`. The core artifact has no Spring,
Micrometer, or Reactor runtime dependency.

## 0.1.0 Compatibility Baseline

The 0.1.0 target supports one explicit runtime combination:

- Java 25 minimum runtime and Java 25 bytecode
- Spring Boot 4.1.0
- Spring AI 2.0.0

This baseline follows the [Oracle Java support roadmap](https://www.oracle.com/java/technologies/java-se-support-roadmap.html),
the [Spring Boot 4.1 system requirements](https://docs.spring.io/spring-boot/system-requirements.html),
and the [Spring AI 2.0 compatibility guidance](https://docs.spring.io/spring-ai/reference/getting-started.html).

`token-pilot-core` remains framework-independent and does not publish Spring
Boot, Spring AI, Micrometer, or Reactor dependencies. Spring Boot 3, Spring AI
1.x, other Spring Boot/Spring AI patch combinations, and older Java runtimes
are not part of the 0.1.0 support guarantee.

The verified Spring AI path is the synchronous `ChatClient` call lifecycle with
a fake provider, including preflight blocking, atomic reservation, dispatch,
and estimate/actual reconciliation. Real-provider compatibility, chunk
accounting, streaming cancellation, and partial-usage reconciliation are not
included in this compatibility claim.

If a provider returns a model different from the request pricing snapshot,
TokenPilot keeps the estimate as `PRICING_RECONCILIATION_REQUIRED` instead of
charging the request model's price. The pending event preserves the provider
usage and response model; an application that has an immutable response-model
pricing snapshot can finish the lifecycle with
`ReservationAccounting.reconcileLateActual(command, responsePricingSnapshot)`.
Mismatched model, currency, pricing terms, state, or duplicate callbacks remain
fail-closed.

## License

Token Pilot is licensed under the [MIT License](LICENSE).
