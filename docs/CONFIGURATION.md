# Token Pilot configuration reference

All Token Pilot Spring Boot properties use the `token-pilot` prefix. The
defaults below are supplied by `TokenPilotProperties` and the owner-specific
auto-configurations.

## Root and pricing

| Property | Default | Meaning |
| --- | --- | --- |
| `token-pilot.enabled` | `true` | Enables the Token Pilot auto-configuration graph. |
| `token-pilot.pricing.plans` | `[]` | Pricing plans bound into the in-memory pricing registry. |
| `token-pilot.pricing.plans[].model-id` | required | Model ID or exact alias resolved by the pricing registry. |
| `token-pilot.pricing.plans[].currency` | required | ISO-4217 currency code used by the plan. |
| `token-pilot.pricing.plans[].rates.PROMPT` | plan-defined | Rate for ordinary input tokens. |
| `token-pilot.pricing.plans[].rates.COMPLETION` | plan-defined | Rate for ordinary output tokens. |
| `token-pilot.pricing.plans[].rates.REASONING` | optional | Rate for reasoning output tokens. |
| `token-pilot.pricing.plans[].rates.CACHE_READ_PROMPT` | optional | Rate for cached input tokens read from cache. |
| `token-pilot.pricing.plans[].rates.CACHE_CREATION_PROMPT` | optional | Rate for input tokens written to cache. |

Rates use `BigDecimal` internally. A missing plan or rate is not silently
converted into a priced zero. The configured missing-pricing policy determines
whether the provider boundary fails closed or records an unpriced result.

```yaml
token-pilot:
  pricing:
    plans:
      - model-id: gpt-4o-mini
        currency: USD
        rates:
          PROMPT: 0.00015
          COMPLETION: 0.00060
```

## Spring AI adapter

| Property | Default | Meaning |
| --- | --- | --- |
| `token-pilot.spring-ai.default-model-id` | unset | Fallback model when the request/provider does not expose a model. |
| `token-pilot.spring-ai.default-reserved-output-tokens` | unset | Fallback output reservation when request options and a resolver provide none. |
| `token-pilot.spring-ai.framing-headroom-tokens` | `0` | Additional conservative headroom for known request framing or advisor additions. |

Resolution order for reserved output is:

```text
ChatOptions.maxTokens → ReservedOutputTokensResolver → configured default
```

Non-positive resolver values are rejected. The current request admission scope
is `TEXT_ONLY`; media, tool messages or schemas, structured-output
augmentation, and enforcement-enabled streaming are rejected before
reservation when they cannot be represented by the supported contract.

## Metrics

| Property | Default | Meaning |
| --- | --- | --- |
| `token-pilot.metrics.enabled` | `true` | Enables Token Pilot-owned meters when a `MeterRegistry` exists. |
| `token-pilot.metrics.tag-whitelist` | `[]` | User tag keys allowed only on the legacy compatibility publisher. |
| `token-pilot.metrics.legacy-ai-token-metrics-enabled` | `false` | Opts into the legacy `ai.token.*` meters. |

The default Token Pilot-owned meters use bounded domain values and do not add
raw model, tenant, user, request, reservation, or idempotency identifiers.
`tag-whitelist` limits keys, not the number of distinct values supplied by an
application.

```yaml
token-pilot:
  metrics:
    enabled: true
    tag-whitelist: []
    legacy-ai-token-metrics-enabled: false
```

## Budget

| Property | Default | Meaning |
| --- | --- | --- |
| `token-pilot.budget.enabled` | `false` | Enables the in-memory atomic reservation and accounting store. |
| `token-pilot.budget.monthly-limit` | `10.00` | Monthly limit for each resolved budget key. |
| `token-pilot.budget.policy-id` | `default-monthly` | Policy identity included in the budget key. |
| `token-pilot.budget.target-type` | `tenant` | Target namespace included in the budget key. |
| `token-pilot.budget.target-tag-key` | `tenant_id` | Request tag used to resolve the target ID. |
| `token-pilot.budget.fallback-target-id` | unset | Explicit fallback when the target tag is absent. Without it, resolution fails closed. |
| `token-pilot.budget.currency` | `USD` | Currency of the monthly limit. |
| `token-pilot.budget.zone-id` | `UTC` | IANA zone used to define monthly window boundaries. |

When enforcement is enabled, the safe preflight upper bound is reserved before
provider dispatch. Actual usage later moves liability into committed cost, or
into a pending reconciliation state when usage is unavailable or needs a
separate pricing snapshot.

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
    zone-id: Asia/Seoul
```

Budget state is process-local and in-memory. It does not provide a durable
store, restart replay, or multi-instance atomicity.

## Notification

| Property | Default | Meaning |
| --- | --- | --- |
| `token-pilot.notification.enabled` | `false` | Enables threshold event deduplication and notification lifecycle events. |

Notification auto-configuration requires a `BudgetNotificationHandler` bean
and an enabled budget accounting graph:

```java
@Component
final class BudgetAlertHandler implements BudgetNotificationHandler {
    @Override
    public void handle(BudgetNotificationEvent event) {
        // Application-owned email, Slack, Webhook, or audit integration.
    }
}
```

Handler failures are isolated from the provider response and accounting
transition. Delivery is process-local, best-effort, and at-most-once.

## Sample `demo` profile

The sample app's `demo` profile supplies a deterministic in-memory `ChatModel`,
enables budget enforcement, and exposes the eight scenario endpoints described
in [QUICKSTART.md](QUICKSTART.md). Its effective configuration is:

```yaml
token-pilot:
  enabled: true
  pricing:
    plans:
      - model-id: gpt-4o-2024-08-06
        currency: USD
        rates:
          PROMPT: 0.00015
          COMPLETION: 0.00060
  spring-ai:
    default-model-id: gpt-4o-2024-08-06
    default-reserved-output-tokens: 16
    framing-headroom-tokens: 0
  budget:
    enabled: true
    policy-id: demo-monthly
    target-type: tenant
    target-tag-key: tenant_id
    fallback-target-id: demo-shared
    monthly-limit: 0.001
    currency: USD
    zone-id: UTC
  metrics:
    enabled: true
    legacy-ai-token-metrics-enabled: false
    tag-whitelist: []
```

Start it with:

```bash
./gradlew --no-daemon :token-pilot-sample-app:bootRun \
  --args='--spring.profiles.active=demo'
```

## Auto-configuration rules

- User-defined beans win over defaults through `@ConditionalOnMissingBean`.
- Optional integrations require their classpath and feature conditions.
- Budget and notification beans are absent by default.
- A custom budget store used for enforcement must also expose the accounting capability; startup fails rather than silently falling back to ledger-only behavior.
- The starter does not select an LLM provider or require an API key by itself.
