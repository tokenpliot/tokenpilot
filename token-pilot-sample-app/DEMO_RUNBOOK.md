# Token Pilot demo runbook

이 데모는 `demo` Spring profile에서만 활성화되는 네트워크 없는 가짜
`ChatModel`을 사용합니다. 별도 자격 증명이나 외부 호출 없이도 context admission,
atomic reservation, idempotency, release, actual reconciliation을 반복해서 확인할 수
있습니다.

## 1. 앱 실행

프로젝트 루트에서 실행합니다.

```bash
./gradlew --no-daemon :token-pilot-sample-app:bootRun \
  --args='--spring.profiles.active=demo'
```

앱은 `http://localhost:8080`에서 대기합니다. 별도 API key는 필요하지 않습니다.

## 2. 한 번에 시나리오 실행

먼저 데모 상태와 시나리오 목록을 보여줍니다.

```bash
curl -s http://localhost:8080/test/token-pilot/demo | jq
```

8개 시나리오를 실행하고 핵심 검증 필드만 출력합니다.

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

핵심 기대 결과는 다음과 같습니다.

- `context-fit` → `FITS`, provider 호출 `0`
- `context-block` → `EXCEEDS`, provider 호출 `0`
- `budget-concurrency` → `admittedRequests=1`, `blockedRequests=7`, `providerInvocations=1`
- `idempotency` → `sameIdempotencyKey=true`, provider 호출 `1`
- `reconciliation-success` → `COMMITTED`
- `reconciliation-failure`, `reconciliation-unknown` → `RECONCILIATION_REQUIRED`

개별 장면을 다시 찍고 싶을 때는 다음 endpoint를 사용합니다.

```bash
curl -s http://localhost:8080/test/token-pilot/demo/context-block | jq
curl -s http://localhost:8080/test/token-pilot/demo/budget-concurrency | jq
curl -s http://localhost:8080/test/token-pilot/demo/idempotency | jq
curl -s http://localhost:8080/test/token-pilot/demo/reconciliation-failure | jq
```

## 3. Prometheus와 Grafana 실행

앱은 호스트에서 실행하고 monitoring container만 띄웁니다.

```bash
docker compose -f token-pilot-sample-app/docker-compose.yml up --build -d
```

확인할 주소는 다음과 같습니다.

- Prometheus target: `http://localhost:9090/targets` → `token-pilot-backend`가 `UP`
- Prometheus query: `http://localhost:9090/graph`
- Grafana: `http://localhost:3000` → `Token Pilot Demo` dashboard 선택

Prometheus는 `host.docker.internal:8080/actuator/prometheus`를 scrape하도록 구성되어
있습니다. 앱의 `/run`을 실행한 뒤 10초 정도 기다리면 Grafana stat과 time-series에
`tokenpilot_*` 값이 표시됩니다.

```bash
curl -s http://localhost:8080/actuator/prometheus \
  | rg '^tokenpilot_(preflight|budget|cost|reconciliation)'
```

검증이 끝나면 monitoring container를 정리합니다.

```bash
docker compose -f token-pilot-sample-app/docker-compose.yml down
```

## 4. 상태 초기화

앱을 종료한 뒤 다시 `bootRun`하면 in-memory budget과 Micrometer counter가
초기화됩니다.
