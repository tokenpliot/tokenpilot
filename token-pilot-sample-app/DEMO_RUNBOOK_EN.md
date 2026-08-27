# Token Pilot demo runbook (English)

This demo uses a network-free fake `ChatModel` that is enabled only in the
`demo` Spring profile. It repeatedly exercises context admission, atomic
reservation, idempotency, release, and actual reconciliation without separate
credentials or external calls.

## 1. Start the application

Run the following from the repository root:

```bash
./gradlew --no-daemon :token-pilot-sample-app:bootRun \
  --args='--spring.profiles.active=demo'
```

The application listens on `http://localhost:8080`. No API key is required.

## 2. Run the scenarios

Show the demo status and scenario index first:

```bash
curl -s http://localhost:8080/test/token-pilot/demo | jq
```

Run all eight scenarios and print the key verification fields:

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
| `idempotency` | `PASS`, the repeated key is reused and the provider is called once |
| `release` | `PASS`, the reservation is released before dispatch |
| `reconciliation-success` | `PASS`, accounting state `COMMITTED` |
| `reconciliation-failure` | `PASS`, accounting state `RECONCILIATION_REQUIRED` |
| `reconciliation-unknown` | `PASS`, accounting state `RECONCILIATION_REQUIRED` |

Run an individual scenario when recording a focused result:

```bash
curl -s http://localhost:8080/test/token-pilot/demo/context-block | jq
curl -s http://localhost:8080/test/token-pilot/demo/budget-concurrency | jq
curl -s http://localhost:8080/test/token-pilot/demo/idempotency | jq
curl -s http://localhost:8080/test/token-pilot/demo/reconciliation-failure | jq
```

## 3. Start Prometheus and Grafana

Keep the application running on the host and start only the monitoring
containers:

```bash
docker compose -f token-pilot-sample-app/docker-compose.yml up --build -d
```

Open these addresses:

- Prometheus targets: `http://localhost:9090/targets` — `token-pilot-backend` should be `UP`
- Prometheus query UI: `http://localhost:9090/graph`
- Grafana: `http://localhost:3000` — select the `Token Pilot Demo` dashboard

Prometheus scrapes `host.docker.internal:8080/actuator/prometheus`. Run the demo
endpoint, wait about ten seconds, and then inspect the `tokenpilot_*` values in
Grafana or directly from the endpoint:

```bash
curl -s http://localhost:8080/actuator/prometheus \
  | rg '^tokenpilot_(preflight|budget|cost|reconciliation)'
```

Stop the monitoring containers when finished:

```bash
docker compose -f token-pilot-sample-app/docker-compose.yml down
```

## 4. Run the OpenAI provider smoke scenario

Stop the `demo` application first with `Ctrl+C`, then start the real OpenAI
provider path. This path uses external network access and an API key; provider
charges may apply. Never put the key in a source file or commit it.

The default smoke model is the Token Pilot catalog entry
`gpt-4o-mini-2024-07-18`. If you choose another model, update the Token Pilot
catalog and pricing configuration to match it.

```bash
export OPENAI_API_KEY='your OpenAI API key'
export OPENAI_MODEL='gpt-4o-mini-2024-07-18'

./gradlew --no-daemon :token-pilot-sample-app:bootRun \
  --args='--spring.profiles.active=openai-smoke'
```

In a second terminal, send one short request:

```bash
curl -sS --get http://localhost:8080/test/token-pilot/openai-smoke \
  --data-urlencode 'prompt=Reply with one short sentence confirming the provider is reachable.' \
  | jq
```

A successful response reports `status=PASS`, a `usageSource` other than
`UNAVAILABLE`, and `accountingState=COMMITTED`. It also includes normalized
provider usage and the resulting accounting cost.

Inspect the resulting meters:

```bash
curl -sS http://localhost:8080/actuator/prometheus \
  | rg '^tokenpilot_(preflight|budget|cost|reconciliation)'
```

The automated smoke test is opt-in and requires both conditions below. A normal
`./gradlew test` run does not make a provider call.

```bash
RUN_OPENAI_SMOKE=true \
  ./gradlew --no-daemon :token-pilot-sample-app:test \
  --tests io.tokenpilot.sample.OpenAiSmokeE2ETest
```

To run both paths for a recording, execute `/test/token-pilot/demo/run` with the
`demo` profile, stop the application, then restart it with `openai-smoke` and
call the smoke endpoint. Both profiles use port `8080`, so do not run them at
the same time.

## 5. Reset the demo state

Restarting `bootRun` resets the in-memory budget store and Micrometer counters.
