# Metrics reference

Token Pilot metrics are an operational view of control and accounting events.
The exact monetary source of truth remains the ledger's `BigDecimal` values.
Micrometer counters use `double` internally.

## Token Pilot-owned meters

| Meter | Prometheus name | Type | Tags | Published when |
| --- | --- | --- | --- | --- |
| `tokenpilot.cost.total` | `tokenpilot_cost_total_currency_total` | Counter | `currency` | A usage-based reservation reconciliation is newly applied as `COMMITTED`. |
| `tokenpilot.preflight.requests` | `tokenpilot_preflight_requests_total` | Counter | `decision`, `reason` | A core context admission decision is emitted. |
| `tokenpilot.budget.reservations` | `tokenpilot_budget_reservations_total` | Counter | `state` | A reservation evaluation result is emitted. |
| `tokenpilot.reconciliation.error.tokens` | `tokenpilot_reconciliation_error_tokens` | DistributionSummary | `direction` | A newly committed reconciliation has an estimate/actual token delta. |
| `tokenpilot.reconciliation.outcomes` | `tokenpilot_reconciliation_outcomes_reconciliations_total` | Counter | `outcome`, `reason` | A reconciliation transition is newly applied. |
| `tokenpilot.pricing.missing` | `tokenpilot_pricing_missing_total` | Counter | `policy` | Missing pricing is observed at the provider boundary. |
| `tokenpilot.listener.failures` | `tokenpilot_listener_failures_total` | Counter | `listener`, `phase` | An optional accounting listener fails and is isolated. |
| `tokenpilot.notification.events` | `tokenpilot_notification_events_total` | Counter | `outcome`, `threshold` | A notification lifecycle result is emitted. |

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

## Demo verification

Start the sample app with the `demo` profile, then run the aggregate endpoint:

```bash
./gradlew --no-daemon :token-pilot-sample-app:bootRun \
  --args='--spring.profiles.active=demo'
curl -s http://localhost:8080/test/token-pilot/demo/run | jq
```

The response should contain eight `PASS` scenarios. The budget concurrency
scenario emits one admitted request and seven `blocked` reservation results;
the successful reconciliation scenario emits `committed`, while the failure
and unknown-usage scenarios emit `reconciliation_required`.

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

The provisioned Grafana dashboard reads the same names from
`token-pilot-sample-app/grafana/provisioning/dashboards/token-pilot-dashboard.json`.
Start it with:

```bash
docker compose -f token-pilot-sample-app/docker-compose.yml up --build -d
```

Micrometer's Prometheus naming convention converts dots to underscores and
adds the appropriate counter or summary suffix. Always verify the rendered
Prometheus name from the endpoint before copying a query into a dashboard.

Do not put prompt text, completion text, request IDs, reservation IDs, raw
provider model strings, or unbounded tenant/user values into metric names or
default tags.
