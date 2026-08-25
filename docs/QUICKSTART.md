# Token Pilot quickstart

Token Pilot has two supported entry paths:

| Path | Use it when | Runtime boundary |
| --- | --- | --- |
| `token-pilot-core` | You need framework-independent token admission and domain contracts | No Spring, Spring AI, Micrometer, or Reactor runtime dependency |
| `token-pilot-starter` | You use Spring Boot and Spring AI | Adds Token Pilot auto-configuration and optional adapters; your application still selects the provider |

The current release candidate targets Java 25, Spring Boot 4.1.0, and Spring AI
2.0.0. The repository defaults to `0.0.1-SNAPSHOT`; use `0.1.0` only for the
release-candidate staging flow described in [RELEASE.md](RELEASE.md).

## Core path

For a local checkout, publish the snapshot artifacts first:

```bash
./gradlew publishToMavenLocal
```

Then use the framework-independent artifact from a plain Java application:

```gradle
repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    implementation 'cloud.token-pilot:token-pilot-core:0.0.1-SNAPSHOT'
}
```

The core API exposes a heuristic UTF-8 estimator, a versioned model catalog,
and a context admission contract:

```java
import io.tokenpilot.core.CoreComponents;
import io.tokenpilot.core.TokenBudget;
import io.tokenpilot.core.TokenEstimator;
import io.tokenpilot.core.domain.BudgetResult;
import io.tokenpilot.core.domain.TokenCountResult;

public final class CoreExample {
    public static void main(String[] args) {
        TokenEstimator estimator = CoreComponents.utf8ByteHeuristicTokenEstimator();
        TokenBudget budget = CoreComponents.tokenBudget(
                CoreComponents.defaultModelRegistry()
        );

        TokenCountResult input = estimator.estimate("hello");
        BudgetResult result = budget.check("gpt-4o-mini", input, 256);

        System.out.println("status=" + result.status());
        System.out.println("reason=" + result.reason());
        System.out.println("model=" + result.canonicalModelId().orElse("unknown"));
        System.out.println("estimator=" + result.estimatorDescriptor());
    }
}
```

The estimator deliberately returns `TEXT_ONLY` and `exact=false`. A short
standalone text can therefore produce `INDETERMINATE` with
`INCOMPLETE_SCOPE`; that is a fail-closed result, not a tokenization error.
The `safeUpperBoundTokens` value is the conservative value used for context
admission. It must not be described as an exact provider tokenizer result.

The admission statuses are:

- `FITS`: the safe input upper bound plus reserved output fits the registered context window.
- `EXCEEDS`: the safe upper bound or reserved output exceeds the context window.
- `INDETERMINATE`: the model, tokenizer compatibility, scope, or token count is not sufficient to authorize the call.

Use `TokenBudget.requireFits(...)` at a provider boundary when a non-`FITS`
result must stop execution by throwing an exception.

## Spring AI starter path

Add the Token Pilot convenience starter and one provider chosen by your
application. Token Pilot does not bundle or select an LLM provider:

```gradle
repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    implementation 'cloud.token-pilot:token-pilot-starter:0.0.1-SNAPSHOT'
    implementation 'org.springframework.ai:spring-ai-starter-model-openai:2.0.0'
}
```

Minimal configuration:

```yaml
spring:
  ai:
    openai:
      api-key: ${OPENAI_API_KEY}

token-pilot:
  enabled: true
  pricing:
    plans:
      - model-id: gpt-4o-mini
        currency: USD
        rates:
          PROMPT: 0.00015
          COMPLETION: 0.00060
  spring-ai:
    default-model-id: gpt-4o-mini
    default-reserved-output-tokens: 256
  metrics:
    enabled: true
    legacy-ai-token-metrics-enabled: false
```

For a supported non-streaming `ChatClient` call, the adapter performs this
lifecycle:

```text
request adaptation
→ TEXT_ONLY scope and framing preflight
→ pricing snapshot resolution
→ context and budget admission
→ atomic reservation when budget enforcement is enabled
→ provider dispatch
→ provider usage normalization
→ actual reconciliation and Token Pilot metrics
```

The verified path uses a fake provider in repository E2E tests and does not
claim real-provider compatibility. Media, tool calls/schema, structured-output
augmentation, and enforcement-enabled streaming fail closed in the current
release candidate. See [SAMPLE_RUNBOOK.md](SAMPLE_RUNBOOK.md) for the local
verification commands.

## Optional budget and notification configuration

Budget enforcement and notifications are disabled by default. A budget-enabled
application must provide a target tag or an explicit fallback target:

```yaml
token-pilot:
  budget:
    enabled: true
    policy-id: default-monthly
    target-type: tenant
    target-tag-key: tenant_id
    fallback-target-id: shared
    monthly-limit: 10.00
    currency: USD
    zone-id: UTC
  notification:
    enabled: true
```

Notification delivery remains application-owned. Implement
`BudgetNotificationHandler` as a Spring bean if threshold events should be
sent to email, Slack, Webhook, or another application service.

## What this release does not claim

- Exact BPE tokenization.
- Full media/tool/structured-output request admission.
- Streaming chunk accounting, cancellation reconciliation, or partial usage reconciliation.
- Durable reservation, notification, or listener delivery.
- Routing, retry/fallback, standalone gateway, or multi-tenant administration.
- A published `token-pilot-spring-ai-starter` artifact. The current artifact is `token-pilot-starter`.
