# Metrics reference

Token Pilot metrics are an operational view of control and accounting events.
The exact monetary source of truth remains the ledger's `BigDecimal` values.
Micrometer counters use `double` internally.

## Token Pilot-owned meters

| Meter | Type | Tags | Published when |
| --- | --- | --- | --- |
| `tokenpilot.cost.total` | Counter | `currency` | A usage-based reservation reconciliation is newly applied as `COMMITTED`. |
| `tokenpilot.preflight.requests` | Counter | `decision`, `reason` | A core context admission decision is emitted. |
| `tokenpilot.budget.reservations` | Counter | `state` | A reservation evaluation result is emitted. |
| `tokenpilot.reconciliation.error.tokens` | DistributionSummary | `direction` | A newly committed reconciliation has an estimate/actual token delta. |
| `tokenpilot.reconciliation.outcomes` | Counter | `outcome`, `reason` | A reconciliation transition is newly applied. |
| `tokenpilot.pricing.missing` | Counter | `policy` | Missing pricing is observed at the provider boundary. |
| `tokenpilot.listener.failures` | Counter | `listener`, `phase` | An optional accounting listener fails and is isolated. |
| `tokenpilot.notification.events` | Counter | `outcome`, `threshold` | A notification lifecycle result is emitted. |

The default user tag whitelist is empty. Domain enum values are normalized to
lowercase except for registered ISO currency codes, which retain values such
as `USD`.

## Reconciliation semantics

`tokenpilot.cost.total` consumes only newly applied actual commits. Duplicate
or reused callbacks do not increase cost again. When actual usage is
unavailable, Token Pilot emits a `reconciliation_required` outcome rather than
recording zero cost or zero token error.

For committed reconciliations, `direction` means:

- `underestimate`: actual input plus output tokens exceed the estimate plus reserved output.
- `overestimate`: actual usage is below the estimate plus reserved output.
- `exact`: the totals match.

Listener failures are observed after the accounting transition. They do not
roll back the transition, stop later listeners, or trigger durable redelivery.

## Legacy compatibility meters

The following meters are disabled in starter/autoconfigure by default:

- `ai.token.usage.total`
- `ai.token.usage.distribution`
- `ai.token.cost.total`

Enable them explicitly during migration:

```yaml
token-pilot:
  metrics:
    legacy-ai-token-metrics-enabled: true
    tag-whitelist: [tenant_id]
```

This compatibility path may overlap Spring AI Observability and allows
application-provided tag values. It is planned for removal in 0.2.0. New
dashboards should use Spring AI's standard provider telemetry for latency,
trace, and provider token usage, and use `tokenpilot.*` for control and
accounting outcomes.

## Prometheus checks

Expose the Actuator endpoint in a Spring Boot application:

```yaml
management:
  endpoints:
    web:
      exposure:
        include: prometheus,health
```

Then inspect the output:

```bash
curl -fsS http://localhost:8080/actuator/prometheus | grep 'tokenpilot_'
```

Micrometer's Prometheus naming convention converts dots to underscores and
adds the appropriate counter or summary suffix. Always verify the rendered
Prometheus name from the endpoint before copying a query into a dashboard.

Do not put prompt text, completion text, request IDs, reservation IDs, raw
provider model strings, or unbounded tenant/user values into metric names or
default tags.
