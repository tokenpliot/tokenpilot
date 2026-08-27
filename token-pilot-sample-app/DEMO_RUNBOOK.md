# Token Pilot demo runbook (Korean)

영문 버전은 [`DEMO_RUNBOOK_EN.md`](DEMO_RUNBOOK_EN.md)에서 확인할 수 있습니다.

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

## 4. OpenAI provider smoke 시나리오

`demo` 앱을 먼저 종료한 뒤(`Ctrl+C`) 실제 OpenAI 호출을 수행합니다. 이 경로는
외부 네트워크와 API key를 사용하며, 호출 비용은 OpenAI 계정에 청구됩니다. API key를
소스 파일이나 커밋에 넣지 말고 환경 변수로만 전달합니다.

기본 smoke 모델은 Token Pilot catalog에 등록된
`gpt-4o-mini-2024-07-18`입니다. 다른 모델을 사용하려면 Token Pilot catalog와
가격 설정도 함께 맞춰야 합니다.

```bash
export OPENAI_API_KEY='발급받은 OpenAI API key'
export OPENAI_MODEL='gpt-4o-mini-2024-07-18'

./gradlew --no-daemon :token-pilot-sample-app:bootRun \
  --args='--spring.profiles.active=openai-smoke'
```

다른 터미널에서 한 번 호출합니다.

```bash
curl -sS --get http://localhost:8080/test/token-pilot/openai-smoke \
  --data-urlencode 'prompt=Reply with one short sentence confirming the provider is reachable.' \
  | jq
```

정상 결과는 `status=PASS`, `usageSource`가 `UNAVAILABLE`이 아닌 값, 그리고
`accountingState=COMMITTED`입니다. 응답에는 provider 응답 토큰 수와 실제 정산 비용도
함께 포함됩니다.

```bash
curl -sS http://localhost:8080/actuator/prometheus \
  | rg '^tokenpilot_(preflight|budget|cost|reconciliation)'
```

자동화된 smoke test를 직접 실행할 때는 반드시 두 조건을 명시합니다. 일반적인
`./gradlew test`에서는 실제 호출이 발생하지 않습니다.

```bash
RUN_OPENAI_SMOKE=true \
  ./gradlew --no-daemon :token-pilot-sample-app:test \
  --tests io.tokenpilot.sample.OpenAiSmokeE2ETest
```

demo와 smoke를 연속으로 확인하려면 `demo` 프로필에서 `/demo/run`을 실행하고 앱을
종료한 다음, 위의 `openai-smoke` 프로필을 다시 시작해 smoke endpoint를 호출합니다.
두 프로필은 같은 `8080` 포트를 사용하므로 동시에 실행하지 않습니다.

## 5. 상태 초기화

앱을 종료한 뒤 다시 `bootRun`하면 in-memory budget과 Micrometer counter가
초기화됩니다.
