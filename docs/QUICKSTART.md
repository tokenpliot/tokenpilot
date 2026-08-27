# Token Pilot 10-minute quickstart

Token Pilot has two entry paths. Use the first one when the application needs
framework-independent contracts, and the second one when it already uses
Spring Boot and Spring AI.

| Path | Use it when | Runtime boundary |
| --- | --- | --- |
| `token-pilot-core` | You need token estimation and context admission without Spring | No Spring, Spring AI, Micrometer, or Reactor runtime dependency |
| `token-pilot-starter` | You use Spring Boot and Spring AI | Adds Token Pilot auto-configuration and optional adapters; the application selects its provider |

The current compatibility baseline is Java 25, Spring Boot 4.1.0, and Spring
AI 2.0.0. The repository default is `0.0.1-SNAPSHOT`; use `0.1.0` only for the
release-candidate staging flow in [RELEASE.md](RELEASE.md).

## 1. Core path

Publish the local snapshot from the repository root:

```bash
./gradlew publishToMavenLocal
```

Then add the framework-independent artifact to a plain Java application:

```gradle
repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    implementation 'cloud.token-pilot:token-pilot-core:0.0.1-SNAPSHOT'
}
```

The core API exposes a UTF-8 byte heuristic estimator, a versioned model
catalog, and a context admission contract:

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

The estimator returns `TEXT_ONLY` and `exact=false`. Its
`safeUpperBoundTokens` value is the conservative value used for context
admission; it is not an exact tokenizer result. Use
`TokenBudget.requireFits(...)` at a provider boundary when every non-`FITS`
result must stop execution.

Admission results are:

- `FITS`: the safe input upper bound plus reserved output fits the registered context window.
- `EXCEEDS`: the safe upper bound or reserved output exceeds the context window.
- `INDETERMINATE`: the model, scope, tokenizer compatibility, or token count is insufficient to authorize the call.

## 2. Starter and sample demo

For a Spring Boot application, add the Token Pilot starter and choose a Spring
AI provider in the application:

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

The repository sample app has a deterministic `demo` profile. It starts with
no application secret and exposes the control/accounting flow as JSON:

```bash
./gradlew --no-daemon :token-pilot-sample-app:bootRun \
  --args='--spring.profiles.active=demo'
```

In another terminal:

```bash
curl -s http://localhost:8080/test/token-pilot/demo | jq
curl -s http://localhost:8080/test/token-pilot/demo/run | jq
```

The aggregate endpoint runs these eight scenarios:

| Scenario | Expected evidence |
| --- | --- |
| `context-fit` | `FITS`, provider invocations `0` |
| `context-block` | `EXCEEDS`, provider invocations `0` |
| `budget-concurrency` | one admitted request, seven blocked requests, one provider invocation |
| `idempotency` | duplicate command reuses the same key and dispatches once |
| `release` | reservation is released before provider dispatch |
| `reconciliation-success` | actual usage becomes `COMMITTED` |
| `reconciliation-failure` | provider failure becomes `RECONCILIATION_REQUIRED` |
| `reconciliation-unknown` | missing usage becomes `RECONCILIATION_REQUIRED` |

The response includes `runId`, `scenarios`, and a current `metrics` snapshot.
Run one scenario directly when a focused result is needed:

```bash
curl -s http://localhost:8080/test/token-pilot/demo/context-block | jq
curl -s http://localhost:8080/test/token-pilot/demo/budget-concurrency | jq
curl -s http://localhost:8080/test/token-pilot/demo/idempotency | jq
curl -s http://localhost:8080/test/token-pilot/demo/reconciliation-failure | jq
```

The complete sample procedure is in
[`token-pilot-sample-app/DEMO_RUNBOOK.md`](../token-pilot-sample-app/DEMO_RUNBOOK.md).

## 3. Prometheus and Grafana

Keep the sample app running and start the monitoring services:

```bash
docker compose -f token-pilot-sample-app/docker-compose.yml up --build -d
```

Then use:

- Prometheus targets: <http://localhost:9090/targets>
- Prometheus query UI: <http://localhost:9090/graph>
- Grafana: <http://localhost:3000>

The provisioned dashboard is named `Token Pilot Demo`. The Prometheus target
scrapes `host.docker.internal:8080/actuator/prometheus`, so run the sample app
on the host before starting the containers.

Check the rendered meters directly:

```bash
curl -s http://localhost:8080/actuator/prometheus \
  | rg '^tokenpilot_(preflight|budget|cost|reconciliation)'
```

Stop the monitoring services after verification:

```bash
docker compose -f token-pilot-sample-app/docker-compose.yml down
```

## 4. Starter configuration

The smallest application configuration selects a model, pricing, and the
reserved output fallback:

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

For a supported non-streaming `ChatClient` call, the adapter applies:

```text
request adaptation
→ TEXT_ONLY scope and framing preflight
→ pricing snapshot resolution
→ context and budget admission
→ atomic reservation when budget enforcement is enabled
→ provider dispatch
→ usage normalization
→ actual reconciliation and Token Pilot metrics
```

The current request admission scope is `TEXT_ONLY`. Media, tool messages or
schemas, structured-output augmentation, and enforcement-enabled streaming
are rejected before reservation when they cannot be represented by the
supported contract.

## 5. Optional budget and notification

Budget enforcement and notifications are disabled by default. Enable them with
an explicit target or fallback target:

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

Notification delivery is application-owned. Implement
`BudgetNotificationHandler` as a Spring bean when threshold events should be
sent to email, Slack, Webhook, or another application service.

For property defaults, conditional bean rules, and failure behavior, continue
with [CONFIGURATION.md](CONFIGURATION.md). For the meter names and Prometheus
queries, see [METRICS.md](METRICS.md).
