# Token Pilot Agent Guide

## Project Summary

Token Pilot is evolving from a Spring AI usage-tracking starter into a framework-independent Java LLM control and accounting core with optional framework and observability adapters.

Current truth: post-call usage normalization, cost calculation, ledger events, Micrometer publishing, Clock-based monthly budget windows, pure budget decisions, typed missing-pricing policies, pricing snapshots, framework-independent token count results, a UTF-8 byte heuristic estimator, a preflight cost-bound projection, versioned model metadata, conservative context admission, a plain-Java core consumer verification path, and the framework-independent in-memory atomic reservation/idempotency foundation are implemented. Candidate-aware request production and estimate/actual cost reconciliation are 30-day MVP targets, not current capabilities.

Distribution direction: publish a framework-independent core and an optional Spring AI convenience starter from the same repository and release train. The existing starter artifact is `token-pilot-starter`; `token-pilot-spring-ai-starter` is only a target name until a compatibility ADR and module change land.

Long-term direction: evolve the verified control/accounting core into a Java-native embeddable gateway SDK and optional standalone runtime. Gateway, routing, retry/fallback, exact BPE, and durable multi-tenant operations are post-MVP roadmap items.

## Source of Truth

Use documents in this order when scope appears to conflict:

1. Current code and tests describe implemented behavior.
2. `docs/30_DAY_MVP_REPORT.md` defines the active 30-day scope and cutline.
3. This `AGENTS.md` defines repository implementation rules and active priorities.
4. `docs/SPRING_AI_OBSERVABILITY_DIFFERENTIATION.md` defines the telemetry/control boundary.
5. `docs/EVOLUTION_PLAN.md` defines the long-term post-MVP roadmap.

Never describe a roadmap item as an implemented or published capability.

## Agent Rules

- Update this `AGENTS.md` whenever a meaningful feature, module, roadmap, or architectural decision changes.
- Prefer interface-first design across module boundaries.
- Keep core domain code precise and dependency-light.
- Use `BigDecimal` for monetary calculations.
- Avoid high-cardinality Micrometer tags by default.
- Do not place business logic in `token-pilot-starter`; keep starter as a thin user entrypoint.
- If implementation classes stay under `internal`, expose them to other modules through deliberate public factories or public configuration APIs.
- Commit messages must be written in Korean unless the user explicitly requests another language.

## Architecture

| Layer | Modules | Responsibility |
| --- | --- | --- |
| API & Domain | `token-pilot-core` | Core models, pricing, cost calculation interfaces, ledger interfaces |
| Control & Policy | `token-pilot-budget`, `token-pilot-notification` | Budget decisions/state and policy notification events |
| Adapter | `token-pilot-spring-ai`, `token-pilot-micrometer` | Integrate core contracts with Spring AI and Micrometer |
| Infrastructure | `token-pilot-autoconfigure`, `token-pilot-starter` | Spring Boot auto-configuration and final user dependency |
| Demo | `token-pilot-sample-app` | Local verification app for starter/autoconfigure integration |

## Architecture Decision: Notification

라이브러리는 알림 이벤트를 발행하고 실제 메일/Slack/Webhook 발송은 사용자 애플리케이션이 담당한다.

- `token-pilot-notification`은 알림 이벤트 발행과 중복 방지 로직만 담당한다.
- 실제 메일/Slack/Webhook 전송은 사용자 애플리케이션의 `BudgetNotificationHandler` 구현체가 담당한다.
- 라이브러리 내부에서 SMTP 설정이나 외부 메일 서비스를 기본 흐름으로 포함하지 않는다.

## Architecture Decision: Framework Independence and Observability

Token Pilot의 제품 포지션은 framework-independent Java LLM control and accounting core이며, Spring AI는 첫 번째 optional adapter다.

- Core, preflight, pricing, budget, and ledger interfaces must not expose or require Spring AI types.
- For the 30-day MVP, estimator contracts, the heuristic implementation, minimal model registry, and context/pricing metadata start in `token-pilot-core`; extract dedicated tokenizer/catalog modules only after the exact-BPE API stabilizes.
- `token-pilot-spring-ai` translates Spring AI requests and `Usage` into Token Pilot core interfaces; it must not own duplicated policy or accounting logic.
- `token-pilot-micrometer` remains optional. Core behavior must work without Micrometer.
- When Spring AI Observability is present, reuse its standard latency, trace, and input/output/total token telemetry instead of emitting duplicate default token metrics.
- The 0.1.0 compatibility baseline is Java 25 bytecode/runtime, Spring Boot 4.1.0, and Spring AI 2.0.0. This is the only supported framework combination until another matrix entry is deliberately added and tested.
- Token Pilot-owned telemetry focuses on cost, preflight decisions, budget reservations, pricing misses, and estimate/actual reconciliation.
- The existing `token-pilot-starter` remains the Spring AI convenience distribution for compatibility. Do not present it as the only product entrypoint.
- `token-pilot-core` and the Spring AI starter must be separately consumable artifacts with the same project version.
- Do not document `token-pilot-spring-ai-starter` as available until its compatibility strategy, module, publication, and external-consumer test exist.
- A future standalone gateway must invoke the same core interfaces without depending on Spring AI.

## Module Status

| Module | Status | Notes |
| --- | --- | --- |
| `token-pilot-core` | Basic implementation complete | Domain records, pricing, calculator, registry, ledger manager, pricing snapshots, versioned model catalog, token count results, UTF-8 byte heuristic estimation, preflight cost-bound projection, conservative context admission, and public plain-Java consumer verification |
| `token-pilot-spring-ai` | Basic implementation complete | Spring AI 2.0.0 `UsageExtractor`, `LedgerAdvisor`, pricing snapshot resolution, response usage recording, reconciliation decisions, and legacy provider-boundary BLOCK enforcement |
| `token-pilot-micrometer` | Basic implementation complete | `MetricsOptions`, tag whitelist, and metric metadata exist; metric ownership must be narrowed |
| `token-pilot-budget` | Basic atomic reservation implementation | Typed monthly keys, Clock/ZoneId windows, pure status/admission decisions, safe-upper-bound reservations, bucket-scoped atomicity, idempotency, and framework-independent snapshots implemented; candidate production, lifecycle reconciliation, and durable stores remain |
| `token-pilot-notification` | Basic implementation complete | Event API and deduplication exist; not yet connected to the full advisor/budget lifecycle |
| `token-pilot-autoconfigure` | Basic implementation complete | Bean registration, property binding, pricing/budget/notification wiring, and `ChatClientBuilderCustomizer` implemented |
| `token-pilot-starter` | Basic implementation complete | Thin final user entrypoint that brings runtime modules together |
| `token-pilot-sample-app` | Basic E2E complete | Direct ledger metrics, budget, and fake Spring AI advisor E2E implemented |

## Current Work Focus

The active scope is the 30-day MVP in `docs/30_DAY_MVP_REPORT.md`. Work in this order:

1. **Correctness baseline** — token total/breakdown invariants, money precision, missing-pricing policy, BLOCK enforcement, budget windows, listener failure policy, and license metadata.
2. **Preflight core** — framework-independent estimator contract, UTF-8 heuristic result with `exact=false`, explicit scope and safe upper bound, versioned model/context metadata, and `TokenBudget.check()`.
3. **Spend control** — atomic reservation state machine, idempotency, concurrency tests, and estimate/actual reconciliation.
4. **Optional integration** — the Java 25/Boot 4.1.0/Spring AI 2.0.0 adapter and autoconfiguration, Token Pilot-owned metrics, plain-Java sample, Spring sample, and external artifact consumption.

Do not start exact BPE, routing, fallback, standalone HTTP gateway, durable stores, or multi-tenant administration before the MVP exit criteria pass.

Packaging remains a release requirement, not the product scope. Keep local Maven publishing, signing, JReleaser, and external-consumer verification healthy while correctness work lands.

Autoconfigure basic implementation has landed. Future work should wire the new core contracts and harden conditions rather than reimplement policy in Spring configuration.

Gradle dependency cleanup has landed. Library modules should not regain app-only Spring Boot plugin, actuator, or Prometheus dependencies from the root build.

Current Micrometer status:

- `MetricsOptions` exists as the small Micrometer options object.
- Default allowed tag keys remain `tenant_id`.
- The existing `MicroCostMetricsPublisher(MeterRegistry)` constructor is preserved.
- Tests cover null/empty tags and multiple allowed tags.
- Metric descriptions and base units should remain stable.
- Current `ai.token.*` metrics may overlap Spring AI Observability; treat renaming/default suppression as an explicit compatibility decision.
- New default metrics should describe Token Pilot-owned cost, preflight, reservation, pricing-miss, and reconciliation outcomes.

## Distribution Contract

Core-only entry path:

```gradle
dependencies {
    implementation 'cloud.token-pilot:token-pilot-core:<version>'
}
```

Current Spring AI convenience entry path:

```gradle
dependencies {
    implementation 'cloud.token-pilot:token-pilot-starter:<version>'
}
```

Repository sample verification uses:

```gradle
dependencies {
    implementation project(':token-pilot-starter')
}
```

Starter should include the modules users need at runtime, especially `token-pilot-autoconfigure`. The starter should not create beans itself.

Distribution requirements:

- Core and starter share one release version but remain independently consumable artifacts.
- Core must resolve without Spring AI, Spring Boot, Micrometer, or Reactor runtime dependencies.
- `token-pilot-budget`, `token-pilot-micrometer`, and other existing modules remain separately publishable for manual composition; do not collapse them into core merely to claim a two-artifact repository.
- Starter may transitively include the Spring AI adapter, autoconfigure, budget, notification, and Micrometer adapter.
- Public rename to `token-pilot-spring-ai-starter` requires an ADR, alias/deprecation plan, publication changes, and tests from a repository-external consumer.

## Autoconfigure Contract

The autoconfigure module provides:

- `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- `TokenPilotAutoConfiguration`
- `TokenPilotProperties`
- Pricing property binding
- Budget property binding
- Notification property binding
- Metrics/tag whitelist property binding
- Conditional beans for:
  - `CostCalculator`
  - `PricingRegistry`
  - `LedgerManager`
  - `UsageExtractor`
  - `LedgerAdvisor`
  - `BudgetEvaluator`
  - `BudgetStateStore`
  - `MicroCostMetricsPublisher`
  - `ChatClientBuilderCustomizer`
  - `NotificationStateStore`
  - `BudgetNotificationService`

Shared configuration prefix:

```yaml
token-pilot:
  enabled: true
```

## Autoconfigure Implementation Notes

Autoconfigure is responsible for wiring the starter dependency graph into a Spring Boot application. It should not implement provider API calls, sample-app-only beans, or production Redis/JDBC budget stores.

Bean registration principles:

- Use `@ConditionalOnMissingBean` so user beans win over defaults.
- Use `@ConditionalOnClass` for optional adapter integrations.
- Use `@ConditionalOnProperty` for feature flags under `token-pilot.*`.
- Register beans by public interface type whenever possible.
- Keep `token-pilot-starter` free of business logic and bean creation.

Default bean graph:

| Bean | Condition | Purpose |
| --- | --- | --- |
| `CostCalculator` | missing bean | Core cost calculation |
| `PricingRegistry` | missing bean | Pricing plan lookup |
| `LedgerManager` | missing bean | Cost and usage recording |
| `UsageExtractor` | Spring AI classpath + missing bean | Spring AI response usage extraction |
| `LedgerAdvisor` | Spring AI classpath + missing bean | ChatClient advisor |
| `MicroCostMetricsPublisher` | Micrometer classpath + `token-pilot.metrics.enabled` | Cost/token metrics listener |
| `BudgetStateStore` | `token-pilot.budget.enabled` + missing bean | Default in-memory budget state |
| `BudgetEvaluator` | `token-pilot.budget.enabled` + missing bean | Default budget evaluator |
| `ChatClientBuilderCustomizer` | Spring AI classpath + `LedgerAdvisor` bean | Adds advisor to ChatClient builders |
| `NotificationStateStore` | `token-pilot.notification.enabled` + missing bean | Window-based notification deduplication state |
| `BudgetNotificationService` | `token-pilot.notification.enabled` + `BudgetNotificationHandler` bean | Publishes budget notification events to user-defined handler |

`core.internal` implementation classes should remain package-private. Cross-module construction should go through `LedgerComponents` or another deliberate public factory/API. Do not make internal implementation classes public just to satisfy autoconfigure access.

Autoconfigure tests should use `ApplicationContextRunner` and verify:

- context starts with default settings
- pricing properties flow into `PricingProvider`, `PricingRegistry`, and `LedgerManager` cost calculation
- user-defined beans are not overridden
- Micrometer publisher registers only when `MeterRegistry` is available and metrics are enabled
- budget beans do not register by default
- budget beans register when budget is enabled
- budget-enabled `LedgerAdvisor` calls `BudgetEvaluator`
- Spring AI classpath registers `UsageExtractor`, `LedgerAdvisor`, and `ChatClientBuilderCustomizer`
- notification beans do not register by default
- notification beans register when notification is enabled and `BudgetNotificationHandler` bean exists

## Notification Contract

사용자는 `BudgetNotificationHandler`를 Spring Bean으로 구현하여 알림을 받을 수 있다.

```java
@Component
class MailBudgetNotificationHandler implements BudgetNotificationHandler {
    @Override
    public void handle(BudgetNotificationEvent event) {
        mailService.sendBudgetAlert(event);
    }
}
```

- `BudgetNotificationHandler` 빈이 없으면 `BudgetNotificationService`는 등록되지 않는다 (no-op).
- `token-pilot.notification.enabled=true` 설정 시에만 notification 빈이 등록된다.
- 알림 중복 방지는 evaluator가 확정한 `BudgetKey(policyId, targetType, targetId, window)`로 처리된다.
- 같은 window 안에서는 낮거나 같은 threshold 재발송이 방지된다.
- 새 window에서는 50/80/100% 알림이 다시 가능하다.

## Recommended Configuration Shape

```yaml
token-pilot:
  enabled: true
  pricing:
    plans:
      - model-id: gpt-4o-mini
        currency: USD
        rates:
          PROMPT: 0.00015
          COMPLETION: 0.00060
  metrics:
    enabled: true
    tag-whitelist:
      - tenant_id
      - model
  budget:
    enabled: false
    policy-id: default-monthly
    target-type: tenant
    target-tag-key: tenant_id
    # fallback-target-id: shared
    monthly-limit: 10.00
    currency: USD
    zone-id: UTC
  notification:
    enabled: false
```

## Sample App Direction

`token-pilot-sample-app` should be a starter integration verification app.

The 30-day MVP also needs a repository-external plain-Java consumer fixture or CI-created project that depends only on `token-pilot-core`. Do not add Spring AI transitively to make that verification pass.

Current endpoints:

- `GET /test/token-pilot/smoke`: app is running and starter is on classpath.
- `GET /test/token-pilot/beans`: reports whether expected autoconfigure beans exist.
- `GET /test/token-pilot/record`: records a deterministic token usage event through `LedgerManager`.
- `GET /test/token-pilot/budget`: exercises budget enabled/limit behavior when budget beans are present.
- `GET /actuator/prometheus`: validates actuator/prometheus exposure.

Test-only E2E endpoint:

- `GET /test/token-pilot/chat`: exercises the Spring AI `ChatClient` advisor path with a fake/mock provider or documented real provider setup.

The direct ledger E2E test verifies that `/actuator/prometheus` contains token-pilot metrics after a ledger event is recorded. The fake ChatClient E2E test verifies that Spring AI `ChatClient` calls flow through `LedgerAdvisor` into token-pilot metrics without requiring a real provider API key.

## Maven Publishing Direction

MVP publishing should proceed in this order:

1. Keep the existing Gradle `maven-publish`, signing, and POM configuration passing.
2. Confirm artifact ids, one shared version, generated POM metadata, license metadata, and runtime dependency scopes.
3. Run `publishToMavenLocal` for both `token-pilot-core` and `token-pilot-starter`.
4. Create CI-based temporary consumer projects for the core-only and starter paths.
5. Verify the core consumer has no Spring AI, Spring Boot, Micrometer, or Reactor runtime dependency.
6. Verify the starter consumer works with one starter dependency plus user-selected Spring AI provider dependencies.
7. Publish snapshots to GitHub Packages and document the credential flow.
8. Verify Maven Central consumption with `mavenCentral()` only before announcing `0.1.0` as released.

The build currently defaults to `0.0.1-SNAPSHOT`; `0.1.0` is the MVP release candidate, not a published fact.

## Roadmap

Active 30-day roadmap:

1. Correctness baseline for usage, money, BLOCK enforcement, budget windows, listener isolation, and license metadata.
2. Framework-independent UTF-8 heuristic estimation with a conservative admission bound, versioned model/context metadata, and `TokenBudget.check()`.
3. Atomic in-memory budget reservation, idempotency, and estimate/actual reconciliation.
4. Optional Spring AI integration, Token Pilot-owned metrics, samples, and two-path artifact verification.

Post-MVP roadmap:

1. Exact byte-level BPE, encoding assets, Unicode corpus expansion, and JMH optimization.
2. Thin OpenAI-compatible gateway slice using provider-independent contracts.
3. Reliable routing with retries, fallback, circuit breakers, streaming cancellation, and attempt-level accounting.
4. Multi-tenant policy, durable stores, OpenTelemetry, and runtime security hardening.

The active checklist is in `docs/30_DAY_MVP_REPORT.md`; detailed long-term workstreams are in `docs/EVOLUTION_PLAN.md`.

## Known Risks

- `core.internal` implementation classes are package-private by design. Cross-module construction should continue through public factory/configuration APIs.
- `TokenUsage` now enforces normalized inclusive totals, optional cache-read/cache-creation/reasoning details, and explicit usage provenance. `DefaultCostCalculator` partitions overlapping totals into disjoint billable amounts before applying rates.
- `Cost` now requires an explicit currency, preserves exact internal `BigDecimal` precision, rejects negative values and cross-currency operations, and defers scale-6 `HALF_UP` rounding to `RoundingPolicy.COST_BOUNDARY_ROUNDING`.
- Budget money interfaces now use `Cost` while preserving `BudgetKey`, `BudgetPolicy`, Clock/ZoneId monthly windows, and per-key policy snapshots.
- The legacy `DefaultLedgerManager.record(String, ...)` path preserves an explicit zero USD fail-open result for a missing plan; the pricing-snapshot path applies `MissingPricingPolicy` and records `UNPRICED` or rejects before provider invocation, so neither behavior is a priced zero-rate plan.
- Spring AI usage extraction converts map/JSON-compatible native usage objects into the normalized core model. Real-provider compatibility fixtures remain required because provider and Spring AI usage shapes can change independently.
- The legacy provider boundary blocks an already-exhausted budget decision before provider invocation. Its candidate-free `STATUS` input is a regression guard, not admission evidence; the flow remains check-then-add and is not connected to the new atomic reservation lifecycle until #39.
- Current Micrometer `ai.token.*` metrics may duplicate Spring AI Observability; preserve compatibility while deciding default suppression or replacement.
- The verified Spring AI 2.0.0 path is synchronous `ChatClient` usage recording with a fake provider. Streaming cancellation and reconciliation remain outside the current compatibility guarantee.
- The repository, README, JReleaser configuration, and every published module POM use the MIT License. `verifyPublicationMetadata` guards this release contract and ensures the sample app is not published.
- Sample app E2E uses a fake Spring AI `ChatModel`; real provider API behavior is not yet verified.
- `token-pilot-spring-ai-starter` does not exist in the current build; never use it as an install instruction until implemented and published.
- Maven Central release consumption must be re-verified for both core and starter paths before announcing `0.1.0`.
- Preflight cost bounds must use one immutable pricing snapshot from calculation through reservation and reconciliation; resolving a mutable registry again by model/policy identifiers can mix prices from different requests.
- A preflight cost bound alone is not context admission evidence. The REQUEST token result and reserved output must also pass `TokenBudget` against the versioned model context before a provider call or reservation is authorized.
- `DefaultPreflightCostEstimator` currently relies on the existing exclusive `TokenType` pricing shape; richer pricing combinations and finite/unbounded policy capabilities must be owned by a validated pricing policy snapshot rather than caller-supplied flags.
- The UTF-8 byte heuristic estimator returns `TEXT_ONLY` and uses `BYTE_LEVEL_BPE_UTF8` only as a byte-level safety-basis identifier; it is not an exact BPE implementation and must not be used as full-request admission evidence.

## Verification

Run all tests:

```bash
./gradlew test
```

Verify the fixed Java/Spring compatibility matrix and generated core-only consumer:

```bash
./gradlew verifyCompatibilityMatrix verifyCoreConsumer
```

Run sample app after implementation work:

```bash
./gradlew :token-pilot-sample-app:bootRun
```

Check Prometheus metrics:

```bash
curl http://localhost:8080/actuator/prometheus
```

Verify both the core-only and starter paths with CI-created temporary consumer projects after the `cloud.token-pilot` artifacts are published locally or remotely.

Verify the snapshot path explicitly:

```bash
./gradlew publishToMavenLocal
```

Then run the CI-created temporary consumer project against `mavenLocal()`.

Publish snapshots to GitHub Packages:

```bash
./gradlew publish \
  -PmavenRepoUrl=https://maven.pkg.github.com/tokenpliot/tokenpilot \
  -PmavenRepoUsername="$GITHUB_ACTOR" \
  -PmavenRepoPassword="$GITHUB_TOKEN"
```

Prepare a signed release build locally:

```bash
./gradlew publishToMavenLocal -PprojectVersion=0.1.0
```

Stage and deploy a Central release:

```bash
./gradlew publishAllPublicationsToStagingRepository -PprojectVersion=0.1.0
./gradlew jreleaserDeploy -PprojectVersion=0.1.0
```

## Update History

### 2026-08-15

- Added the framework-independent `BudgetStateStore` atomic safe-upper-bound reservation contract with immutable reservation IDs, idempotency fingerprints, bucket-scoped concurrency control, currency-safe outcomes, and effective-usage snapshots.
- Kept the existing committed-cost APIs compatible while leaving commit/release and estimate/actual reconciliation to #37.

### 2026-08-14

- Added the immutable versioned `ModelRegistry`/`ModelDefinition` catalog, canonical alias lookup, pricing-policy and currency binding, and the minimal official-source model catalog.
- Added `TokenBudget.check()` with overflow-safe context admission, explicit FITS/EXCEEDS/INDETERMINATE results, tokenizer compatibility checks, and fail-closed `requireFits()` behavior.
- Added a public framework-independent core factory, a plain-Java preflight consumer check, and a README quickstart without Spring runtime dependencies.

### 2026-08-10

- Added the UTF-8 byte heuristic estimator and made preflight cost calculation consume the exact immutable pricing snapshot carried by its context instead of re-resolving a mutable registry.
- Kept arithmetic failures inside the typed preflight unavailable-result contract for pricing inputs whose decimal scale cannot be represented by the calculation.

### 2026-08-09

- Clarified that the preflight cost-bound projection is not context admission or atomic reservation, and documented the immutable pricing-snapshot requirement for later integration.

### 2026-08-08

- Added framework-independent token count result contracts and conservative preflight cost bounds that preserve immutable pricing metadata, use exact decimal arithmetic, and return typed unavailable outcomes instead of zero-price fallbacks.

### 2026-08-04

- Added typed missing-pricing policies, immutable pricing snapshots, core rate validation/reconciliation decisions, and Spring AI pre-call pricing resolution; `FAIL_CLOSED` rejects missing plans/rates before provider invocation and `FAIL_OPEN` preserves `UNPRICED`.
- Preserved the deprecated `BudgetNotificationEvent.currentUsage()` compatibility accessor through 0.1.x while migrating handlers to `projectedUsage()`; removal is planned for 0.2.0.

### 2026-07-29

- Separated pure budget decisions from provider-boundary enforcement: evaluator BLOCK and currency mismatch outcomes now return structured decisions.
- Distinguished committed usage from candidate-inclusive projected usage and marked candidate-free status results as non-admission evidence.
- Added legacy Spring AI BLOCK enforcement before provider invocation while leaving candidate-aware admission, atomic reservation, and reconciliation to #39, #36, and #37.

### 2026-07-27

- Fixed the 0.1.0 compatibility baseline to Java 25 bytecode/runtime, Spring Boot 4.1.0, and Spring AI 2.0.0.
- Migrated Spring AI integration to the 2.0 APIs, including `ChatClientBuilderCustomizer` and Jackson 3-compatible native usage conversion.
- Added build-time verification for Java 25 class files, exact Spring dependency resolution, framework-free core publication metadata, and a generated core-only Java 25 consumer.
- Removed per-request cost rounding, added explicit external-boundary rounding, and made `Cost` amount/currency invariants explicit.
- Migrated budget policy, decision, state-store, notification, Spring AI, autoconfigure, and sample money interfaces to `Cost` without removing the existing `BudgetKey` and Clock-based monthly-window design.
- Standardized repository and Maven publication metadata on the MIT License.
- Added artifact-specific POM descriptions and structured generated-POM verification for all seven public modules.
- Added the publication metadata check to the normal Gradle `check` lifecycle and verified that the sample app remains unpublished.

### 2026-07-22

- Added immutable monthly `BudgetKey`/`BudgetWindow` identity resolved from an injected `Clock` and configured `ZoneId`.
- Made budget policy identity, target fallback, limit, and currency explicit, and propagated the resolved key through state updates and notifications.

### 2026-07-20

- Replaced generic cached input/cached output details with optional cache-read input, cache-creation input, and reasoning-output breakdowns; `null` now means unreported and `0` means reported zero.
- Added `UsageSource`, provider-specific total normalization in the Spring AI adapter, and disjoint cost partitioning to prevent details from being charged twice.
- Positioned Token Pilot as a framework-independent Java LLM control and accounting core with Spring AI as an optional adapter.
- Chose to reuse Spring AI Observability for standard latency, trace, and token telemetry while keeping Token Pilot metrics focused on cost, policy, budget, and reconciliation.
- Kept the existing starter as a Spring AI convenience distribution rather than the sole product identity.
- Defined `token-pilot-core` and the current `token-pilot-starter` as separate, same-version distribution paths; reserved `token-pilot-spring-ai-starter` as a compatibility decision rather than a current artifact.
- Reframed the active 30-day MVP around correctness, heuristic preflight, context admission, atomic spend reservation, reconciliation, optional Spring AI integration, and release evidence.
- Added `docs/30_DAY_MVP_REPORT.md` as the active scope source and kept gateway, exact BPE, routing, and durable operations in the post-MVP roadmap.

### 2026-07-14

- Added a proposed evolution plan for a Java-native embeddable LLM gateway SDK and standalone Spring Boot runtime.
- Defined headcount-independent workstreams for core contracts, token intelligence, accounting, provider compatibility, routing, policy, runtime, observability, ecosystem integration, and quality engineering.
- Made usage, money, atomic budget reservation, retry/fallback, and streaming correctness the required baseline before provider expansion.
- Positioned LiteLLM as a product and capability benchmark rather than a dependency or a feature-parity target.

### 2026-07-03

- Renamed project branding to Token Pilot.
- Renamed Gradle modules to the `token-pilot-*` pattern.
- Moved Java packages to `io.tokenpilot`.
- Changed the Spring configuration prefix to `token-pilot.*`.
- Updated GitHub repository metadata to `tokenpliot/tokenpilot` and Maven coordinates to the target `cloud.token-pilot:*` namespace.
- Moved sample deployment infrastructure and root local Docker helper scripts to https://github.com/tokenpliot/tokenpilot-demo-infra.
- Removed the repository-managed external consumer fixture; future published artifact checks should use a CI-created temporary consumer project.

### 2026-06-08

- Added `token-pilot-notification` as an event-based notification API with user-provided `BudgetNotificationHandler` implementations.
- Kept notification delivery channels such as SMTP, Slack, and Webhook outside the default library flow; applications own concrete delivery.
- Added autoconfigure wiring for notification state and service beans behind `token-pilot.notification.enabled=true` and a user handler bean.
- Removed Redis budget store work from the MVP notification path so `token-pilot-budget` remains dependency-light.

### 2026-05-23

- Hardened Spring AI usage extraction for null responses, missing metadata, missing usage, and native provider usage preservation.
- Added `MetricsOptions` for Micrometer publisher configuration while preserving existing constructors and default `tenant_id` tag behavior.
- Added Micrometer tests for null tags, empty tags, and multiple allowed tag keys.

### 2026-05-11

- Added Gradle `maven-publish` configuration for library modules with shared POM metadata and optional remote repository credentials.
- Added a repository-managed verification module that depends on published `cloud.token-pilot:token-pilot-starter:0.0.1-SNAPSHOT` from `mavenLocal()`.
- Chose GitHub Packages as the first remote snapshot repository target and documented the publish command in `README.md`.
- Added GitHub Packages consumer examples and expanded published POM metadata for later Maven Central promotion.
- Switched the repository-managed verification module to use `project(':token-pilot-starter')` by default and require an explicit published-artifact verification flag so CI builds do not fail before publish.
- Prepared `cloud.token-pilot:token-pilot-starter:0.0.1` Central consumption wiring. A direct Maven Central lookup on 2026-07-20 returned not found, so do not treat `0.0.1` as a published release.
- Added Gradle `signing` integration and `projectVersion` override support so release builds can be produced with local GPG material before Central Portal upload wiring is finalized.
- Prefer `signingKeyFile` over inline `signingKey` for local release signing because multiline armored keys are less error-prone when loaded from a file.
- Added JReleaser Gradle integration targeting the Central Publisher Portal with `build/staging-deploy` staging repositories and `cloud.token-pilot` namespace wiring.

### 2026-05-04

- Merged basic autoconfigure implementation for property binding, conditional bean registration, pricing registry wiring, budget-aware advisor creation, and ChatClient customization.
- Added sample app direct ledger, budget, and fake Spring AI ChatClient E2E verification for starter endpoints, `LedgerManager.record(...)`, `LedgerAdvisor`, Micrometer listener wiring, Prometheus `ai.token.*` metrics, and budget block behavior.
- Cleaned Gradle dependencies so app-only Spring Boot plugin, actuator, and Prometheus dependencies are scoped to the sample app instead of every library module.
- Reframed MVP roadmap around Maven publishing and external consumer validation.

### 2026-04-30

- Renamed project guidance from `GEMINI.md` to `AGENTS.md`.
- Added README roadmap for current project gaps.
- Added starter-focused workstream guidance.
- Clarified that `token-pilot-starter` is the current user-entrypoint task while autoconfigure is owned separately.
- Added a README autoconfigure implementation guide covering bean registration, property binding, internal factory options, and test expectations.
- Implemented Micrometer tag whitelist support and metric description/base unit metadata; documented the next options-object step.

### 2026-04-19

- Moved core domain records into `io.tokenpilot.core.domain`.
- Tightened visibility of core internal default implementations.
- Updated dependent modules to use the new domain package structure.
- Verified tests after the package refactor.

### 2026-04-14

- Added token type support including prompt, completion, reasoning, and cached tokens.
- Added token-type-specific pricing fallback logic.
- Updated Spring AI integration for Spring AI 1.1.4 module split.
- Added usage extraction and advisor tests.
