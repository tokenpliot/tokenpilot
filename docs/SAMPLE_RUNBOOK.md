# Sample app runbook

The sample app is a local starter/autoconfiguration verification app. It is
not a production deployment template and it does not claim real-provider
compatibility.

## Run the automated verification

Use Java 25 and run the repository tests:

```bash
./gradlew test
```

The Spring AI E2E tests use a fake `ChatModel`, so no provider API key is
required. They cover preflight blocking, atomic reservation, actual usage
reconciliation, pricing misses, unsupported request scope, listener isolation,
and enforcement-enabled streaming rejection.

## Start the application

```bash
./gradlew :token-pilot-sample-app:bootRun
```

The default server listens on port `8080`.

Basic checks:

```bash
curl -fsS http://localhost:8080/test/token-pilot/smoke
curl -fsS http://localhost:8080/test/token-pilot/beans
curl -fsS http://localhost:8080/test/token-pilot/record
curl -fsS http://localhost:8080/actuator/health
curl -fsS http://localhost:8080/actuator/prometheus | grep 'tokenpilot_'
```

Expected default properties include:

- Token Pilot enabled.
- One `gpt-4o-mini` USD pricing plan.
- Token Pilot-owned metrics enabled.
- Legacy `ai.token.*` metrics disabled.
- Budget and notification disabled.

## Start Prometheus and Grafana

Keep the sample app running, then start the monitoring containers:

```bash
docker compose -f token-pilot-sample-app/docker-compose.yml up --build
```

Open:

- Prometheus: <http://localhost:9090>
- Grafana: <http://localhost:3000>

The Prometheus configuration scrapes the host application through
`host.docker.internal:8080`. If the target is down, first confirm that the
Spring application is running and that the Actuator endpoint returns HTTP 200.

The dashboard migration from the legacy `ai_token_*` panels to the current
`tokenpilot_*` control/accounting meters is tracked separately in
[issue #44](https://github.com/tokenpliot/tokenpilot/issues/44).
Use [METRICS.md](METRICS.md) and the Prometheus endpoint as the source of truth
until that dashboard migration is complete.

## Budget check

The sample application's `/test/token-pilot/budget` endpoint exercises the
budget demo path only when budget auto-configuration is enabled in the test
profile. For a real enforcement test, use the Spring AI fake-provider E2E
configuration, which enables the atomic reservation store and notification
lifecycle together.

## Provider smoke policy

The default test and local runbook do not call an external provider. A real
provider smoke test must be an explicit profile with:

- one selected Spring AI provider dependency;
- an API key supplied through an environment variable or secret manager;
- a documented model and pricing plan;
- a clear statement that the test is compatibility evidence, not part of the no-secret CI gate.

Never commit an API key or provider-specific credential to this repository.

## Troubleshooting

### `ledgerAdvisor` is absent

Confirm that Spring AI adapter classes are on the classpath and
`token-pilot.enabled=true`. The starter supplies the auto-configuration; the
sample app should not create the advisor manually.

### Budget beans are absent

Set `token-pilot.budget.enabled=true`. If enforcement is enabled, ensure the
budget policy has a target tag or `fallback-target-id`.

### No `tokenpilot_*` metrics appear

Check that a `MeterRegistry` is present, `token-pilot.metrics.enabled=true`,
and the relevant event has actually occurred. A cost meter appears only after
a newly applied actual reservation commit; a legacy cost-only ledger record is
not sufficient.

### The dashboard shows no data

Check Prometheus `/targets`, then query the rendered metric names directly in
Prometheus. The dashboard must use the current `tokenpilot_*` names and the
same application labels emitted by the sample app.
