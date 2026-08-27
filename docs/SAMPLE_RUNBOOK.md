# Sample app runbook

`token-pilot-sample-app` is the repository verification application for the
starter, auto-configuration, control lifecycle, and Token Pilot-owned metrics.
Use the default profile for the basic endpoints and the `demo` profile for the
deterministic admission, reservation, and reconciliation flow.

## Prerequisites

- Java 25
- Docker Desktop when Prometheus and Grafana are needed
- `curl` and `jq` for readable JSON output

## Run automated verification

Run the sample app tests from the repository root:

```bash
./gradlew --no-daemon :token-pilot-sample-app:test
```

The complete repository gate is:

```bash
./gradlew --no-daemon test
```

The tests cover preflight blocking, atomic reservation, actual usage
reconciliation, pricing misses, unsupported request scope, listener isolation,
and enforcement-enabled streaming rejection. `DemoScenarioE2ETest` covers the
HTTP contract for all eight demo scenarios.

## Default profile

Start the sample app without an active profile:

```bash
./gradlew --no-daemon :token-pilot-sample-app:bootRun
```

The server listens on port `8080`. Verify the starter and Actuator endpoints:

```bash
curl -fsS http://localhost:8080/test/token-pilot/smoke
curl -fsS http://localhost:8080/test/token-pilot/beans
curl -fsS http://localhost:8080/test/token-pilot/record
curl -fsS http://localhost:8080/actuator/health
curl -fsS http://localhost:8080/actuator/prometheus \
  | rg '^tokenpilot_'
```

The default profile enables Token Pilot and Token Pilot-owned metrics, loads
the `gpt-4o-mini` pricing example, and leaves budget and notification
enforcement disabled.

## Deterministic demo profile

Stop the default app if it is running, then start the `demo` profile:

```bash
./gradlew --no-daemon :token-pilot-sample-app:bootRun \
  --args='--spring.profiles.active=demo'
```

The demo profile uses the in-memory `DemoChatModel` and does not require an
application secret. Check the index first:

```bash
curl -s http://localhost:8080/test/token-pilot/demo | jq
```

Run the complete scenario set:

```bash
curl -s http://localhost:8080/test/token-pilot/demo/run | jq '{
  runId,
  scenarios: [.scenarios[] | {
    scenario,
    status,
    providerInvocations: .evidence.providerInvocations,
    admittedRequests: .evidence.admittedRequests,
    blockedRequests: .evidence.blockedRequests,
    accountingState: .evidence.accountingState,
    budget: .evidence.budget
  }],
  metrics
}'
```

Expected results:

| Scenario | Expected result |
| --- | --- |
| `context-fit` | `PASS`, admission `FITS`, provider invocations `0` |
| `context-block` | `PASS`, admission `EXCEEDS`, provider invocations `0` |
| `budget-concurrency` | `PASS`, one admitted request, seven blocked requests, one provider invocation |
| `idempotency` | `PASS`, the repeated key dispatches once |
| `release` | `PASS`, the reservation is released before dispatch |
| `reconciliation-success` | `PASS`, accounting state `COMMITTED` |
| `reconciliation-failure` | `PASS`, accounting state `RECONCILIATION_REQUIRED` |
| `reconciliation-unknown` | `PASS`, accounting state `RECONCILIATION_REQUIRED` |

Run a focused scenario through its individual endpoint:

```bash
curl -s http://localhost:8080/test/token-pilot/demo/context-fit | jq
curl -s http://localhost:8080/test/token-pilot/demo/context-block | jq
curl -s http://localhost:8080/test/token-pilot/demo/budget-concurrency | jq
curl -s http://localhost:8080/test/token-pilot/demo/idempotency | jq
curl -s http://localhost:8080/test/token-pilot/demo/release | jq
curl -s http://localhost:8080/test/token-pilot/demo/reconciliation-success | jq
curl -s http://localhost:8080/test/token-pilot/demo/reconciliation-failure | jq
curl -s http://localhost:8080/test/token-pilot/demo/reconciliation-unknown | jq
```

The endpoint list and the effective profile configuration are also maintained
in the [Korean demo runbook](../token-pilot-sample-app/DEMO_RUNBOOK.md) and the
[English demo runbook](../token-pilot-sample-app/DEMO_RUNBOOK_EN.md).

## OpenAI provider smoke profile

Stop the demo app before starting this profile. The sample app selects the
OpenAI provider only for `openai-smoke`; the default and `demo` profiles keep
provider auto-configuration disabled so fake-provider tests remain deterministic.

Set the API key in the environment. Do not put it in a source file or commit it.
The default model is the versioned catalog entry
`gpt-4o-mini-2024-07-18`; each request is a real provider call and may incur
provider charges.

```bash
export OPENAI_API_KEY='your OpenAI API key'
export OPENAI_MODEL='gpt-4o-mini-2024-07-18'

./gradlew --no-daemon :token-pilot-sample-app:bootRun \
  --args='--spring.profiles.active=openai-smoke'
```

In another terminal, run one short request:

```bash
curl -sS --get http://localhost:8080/test/token-pilot/openai-smoke \
  --data-urlencode 'prompt=Reply with one short sentence confirming the provider is reachable.' \
  | jq
```

The response is a pass when it reports `status=PASS`, a non-`UNAVAILABLE`
`usageSource`, and `accountingState=COMMITTED`. It also returns normalized
input/output/total tokens, the newly applied cost metric delta, and the budget
snapshot. Inspect the rendered meters with:

```bash
curl -sS http://localhost:8080/actuator/prometheus \
  | rg '^tokenpilot_(preflight|budget|cost|reconciliation)'
```

The opt-in JUnit smoke test uses the same endpoint and is guarded by both
`RUN_OPENAI_SMOKE=true` and `OPENAI_API_KEY`:

```bash
RUN_OPENAI_SMOKE=true \
  ./gradlew --no-daemon :token-pilot-sample-app:test \
  --tests io.tokenpilot.sample.OpenAiSmokeE2ETest
```

To run both paths, execute `/test/token-pilot/demo/run` while the `demo` profile
is active, stop that process, then restart with `openai-smoke` and call the
smoke endpoint. They intentionally share port `8080` and should not run at the
same time.

## Prometheus and Grafana

Keep the app running and start the monitoring containers from the repository
root:

```bash
docker compose -f token-pilot-sample-app/docker-compose.yml up --build -d
```

Open the following addresses:

- Prometheus targets: <http://localhost:9090/targets>
- Prometheus query UI: <http://localhost:9090/graph>
- Grafana: <http://localhost:3000>

The Prometheus target is `token-pilot-backend` and scrapes
`host.docker.internal:8080/actuator/prometheus`. Run `/test/token-pilot/demo/run`
first, then inspect the current `tokenpilot_*` meters:

```bash
curl -s http://localhost:8080/actuator/prometheus \
  | rg '^tokenpilot_(preflight|budget|cost|reconciliation)'
```

The Grafana container provisions the `Token Pilot Demo` dashboard from
`token-pilot-sample-app/grafana/provisioning/dashboards/token-pilot-dashboard.json`.
It is configured with the Prometheus data source and uses the current
Token Pilot-owned meter names.

Stop the monitoring containers when finished:

```bash
docker compose -f token-pilot-sample-app/docker-compose.yml down
```

## Reset and troubleshooting

The demo budget store and Micrometer counters are in memory. Restart the
sample app to begin with a clean scenario state.

### `ledgerAdvisor` is absent

Confirm that the starter is on the classpath and `token-pilot.enabled=true`.
The sample app should not create the advisor manually.

### Budget beans are absent

The default profile intentionally disables enforcement. Use the `demo` profile,
or set `token-pilot.budget.enabled=true` and provide a target tag or
`fallback-target-id`.

### No `tokenpilot_*` meters appear

Check that Actuator and the Prometheus registry are present,
`token-pilot.metrics.enabled=true`, and that a relevant event has occurred. A
cost meter appears after a newly applied usage-based reservation commit; a
legacy cost-only ledger record is not sufficient.

### The dashboard shows no data

Check Prometheus `/targets` first. The target must be `UP`, and the sample app
must be reachable at port `8080`. Query the rendered names directly in
Prometheus before changing a dashboard panel.
