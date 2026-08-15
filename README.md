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
Spring AI usage integration, model cost calculation, metrics, basic budget
policy, Spring Boot autoconfiguration, and a framework-independent in-memory
atomic budget reservation/idempotency foundation. The MVP is extending this
foundation with provider-connected preflight control and actual usage
reconciliation.

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
a fake provider. Streaming cancellation, provider-connected preflight
blocking, reservation lifecycle reconciliation, and estimate/actual
reconciliation are still MVP work and are not included in this compatibility
claim.

## License

Token Pilot is licensed under the [MIT License](LICENSE).
