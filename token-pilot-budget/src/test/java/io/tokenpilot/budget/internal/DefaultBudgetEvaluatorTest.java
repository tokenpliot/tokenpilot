package io.tokenpilot.budget.internal;

import io.tokenpilot.budget.BudgetDecision;
import io.tokenpilot.budget.BudgetDecision.EvaluationType;
import io.tokenpilot.budget.BudgetKey;
import io.tokenpilot.budget.BudgetPolicy;
import io.tokenpilot.budget.BudgetState;
import io.tokenpilot.budget.BudgetStateStore;
import io.tokenpilot.budget.BudgetThreshold;
import io.tokenpilot.budget.BudgetWindow;
import io.tokenpilot.core.domain.Cost;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefaultBudgetEvaluatorTest {

  private static final Currency USD = Currency.getInstance("USD");
  private static final Map<String, String> TAGS = Map.of("tenant_id", "tenant-a");

  private BudgetStateStore store;

  @BeforeEach
  void setUp() {
    store = mock(BudgetStateStore.class);
  }

  @ParameterizedTest
  @CsvSource({
      "10.00, 20.00, 30.00, ALLOW, NONE",
      "70.00, 10.00, 80.00, WARN, WARNING"
  })
  void 한도_미만의_예상_비용을_포함해_ALLOW와_WARN을_판정한다(
      String committed,
      String candidate,
      String projected,
      BudgetState expectedState,
      BudgetThreshold expectedThreshold
  ) {
    DefaultBudgetEvaluator evaluator = evaluator(policy(null, ZoneOffset.UTC), "2026-07-22T00:00:00Z");
    when(store.getAccumulatedCost(any(), any())).thenReturn(usd(committed));

    BudgetDecision result = evaluator.evaluate(TAGS, usd(candidate));

    assertThat(result.state()).isEqualTo(expectedState);
    assertThat(result.threshold()).isEqualTo(expectedThreshold);
    assertThat(result.key()).isEqualTo(key("policy-a", "tenant-a", "2026-07"));
    assertThat(result.evaluationType()).isEqualTo(EvaluationType.ADMISSION);
    assertThat(result.committedUsage()).isEqualTo(usd(committed));
    assertThat(result.projectedUsage()).isEqualTo(usd(projected));
    verify(store, never()).addCost(any(), any(), any());
  }

  @ParameterizedTest
  @CsvSource({
      "90.00, 10.00, 100.00",
      "95.00, 10.00, 105.00"
  })
  void 예상_사용량이_한도와_같거나_크면_예외_없이_BLOCK을_반환한다(
      String committed,
      String candidate,
      String projected
  ) {
    DefaultBudgetEvaluator evaluator = evaluator(policy(null, ZoneOffset.UTC), "2026-07-22T00:00:00Z");
    when(store.getAccumulatedCost(any(), any())).thenReturn(usd(committed));

    BudgetDecision decision = evaluator.evaluate(TAGS, usd(candidate));

    assertThat(decision.state()).isEqualTo(BudgetState.BLOCK);
    assertThat(decision.threshold()).isEqualTo(BudgetThreshold.EXCEEDED);
    assertThat(decision.evaluationType()).isEqualTo(EvaluationType.ADMISSION);
    assertThat(decision.key()).isEqualTo(key("policy-a", "tenant-a", "2026-07"));
    assertThat(decision.committedUsage()).isEqualTo(usd(committed));
    assertThat(decision.projectedUsage()).isEqualTo(usd(projected));
    assertThat(decision.limit()).isEqualTo(usd("100.00"));
    verify(store, never()).addCost(any(), any(), any());
  }

  @Test
  void 후보_없는_상태_조회는_admission_결과가_아니다() {
    DefaultBudgetEvaluator evaluator = evaluator(policy(null, ZoneOffset.UTC), "2026-07-22T00:00:00Z");
    when(store.getAccumulatedCost(any(), any())).thenReturn(usd("40.00"));

    BudgetDecision decision = evaluator.evaluate(TAGS);

    assertThat(decision.evaluationType()).isEqualTo(EvaluationType.STATUS);
    assertThat(decision.isAdmissionDecision()).isFalse();
    assertThat(decision.committedUsage()).isEqualTo(usd("40.00"));
    assertThat(decision.projectedUsage()).isEqualTo(decision.committedUsage());
  }

  @Test
  void target_identity_누락은_fail_closed다() {
    DefaultBudgetEvaluator evaluator = evaluator(policy(null, ZoneOffset.UTC), "2026-07-22T00:00:00Z");

    assertThatThrownBy(() -> evaluator.evaluate(Map.of("service", "chat")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("tenant_id");

    verify(store, never()).getAccumulatedCost(any(), any());
  }

  @Test
  void 명시적_fallback이_설정된_경우에만_fallback_bucket을_사용한다() {
    DefaultBudgetEvaluator evaluator = evaluator(
        policy("shared-fallback", ZoneOffset.UTC),
        "2026-07-22T00:00:00Z"
    );
    when(store.getAccumulatedCost(any(), any())).thenReturn(Cost.zero(USD));

    BudgetDecision decision = evaluator.evaluate(Map.of("service", "chat"));

    assertThat(decision.key().targetId()).isEqualTo("shared-fallback");
  }

  @ParameterizedTest
  @CsvSource({
      "2026-07-31T23:59:59Z, 2026-07",
      "2026-08-01T00:00:00Z, 2026-08",
      "2028-02-29T23:59:59Z, 2028-02",
      "2028-03-01T00:00:00Z, 2028-03",
      "2026-12-31T23:59:59Z, 2026-12",
      "2027-01-01T00:00:00Z, 2027-01"
  })
  void Clock_fixed로_월_경계를_결정한다(String instant, String expectedWindow) {
    DefaultBudgetEvaluator evaluator = evaluator(policy(null, ZoneOffset.UTC), instant);
    when(store.getAccumulatedCost(any(), any())).thenReturn(Cost.zero(USD));

    BudgetDecision decision = evaluator.evaluate(TAGS);

    assertThat(decision.key().window()).isEqualTo(BudgetWindow.parse(expectedWindow));
  }

  @Test
  void UTC와_설정_ZoneId의_월이_다르면_설정_ZoneId를_따른다() {
    String instant = "2026-07-31T15:30:00Z";
    when(store.getAccumulatedCost(any(), any())).thenReturn(Cost.zero(USD));

    BudgetDecision utc = evaluator(policy(null, ZoneOffset.UTC), instant).evaluate(TAGS);
    BudgetDecision seoul = evaluator(policy(null, ZoneId.of("Asia/Seoul")), instant).evaluate(TAGS);

    assertThat(utc.key().window()).isEqualTo(BudgetWindow.parse("2026-07"));
    assertThat(seoul.key().window()).isEqualTo(BudgetWindow.parse("2026-08"));
  }

  @Test
  void 동시_요청에서도_key_생성_결과가_결정적이다() throws Exception {
    InMemoryBudgetStateStore stateStore = new InMemoryBudgetStateStore();
    DefaultBudgetEvaluator evaluator = new DefaultBudgetEvaluator(
        stateStore,
        policy(null, ZoneOffset.UTC),
        Clock.fixed(Instant.parse("2026-07-22T00:00:00Z"), ZoneOffset.UTC)
    );
    Set<BudgetKey> keys = ConcurrentHashMap.newKeySet();
    List<Future<BudgetKey>> futures = new ArrayList<>();

    try (var executor = Executors.newFixedThreadPool(8)) {
      for (int index = 0; index < 200; index++) {
        futures.add(executor.submit(() -> evaluator.evaluate(TAGS).key()));
      }
      executor.shutdown();
      for (Future<BudgetKey> future : futures) {
        keys.add(future.get(5, TimeUnit.SECONDS));
      }
      assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }

    assertThat(keys).containsExactly(key("policy-a", "tenant-a", "2026-07"));
  }

  @Test
  void 다른_통화의_예상_비용은_mismatch로_반환한다() {
    DefaultBudgetEvaluator evaluator = evaluator(policy(null, ZoneOffset.UTC), "2026-07-22T00:00:00Z");
    when(store.getAccumulatedCost(any(), any())).thenReturn(usd("10.00"));

    BudgetDecision decision = evaluator.evaluate(
        TAGS,
        Cost.of(new BigDecimal("1000"), Currency.getInstance("KRW"))
    );

    assertThat(decision.state()).isEqualTo(BudgetState.CURRENCY_MISMATCH);
    assertThat(decision.evaluationType()).isEqualTo(EvaluationType.ADMISSION);
    assertThat(decision.committedUsage()).isEqualTo(usd("10.00"));
    assertThat(decision.projectedUsage()).isEqualTo(decision.committedUsage());
    assertThat(decision.limit()).isEqualTo(usd("100.00"));
    verify(store, never()).addCost(any(), any(), any());
  }

  private DefaultBudgetEvaluator evaluator(BudgetPolicy policy, String instant) {
    return new DefaultBudgetEvaluator(
        store,
        policy,
        Clock.fixed(Instant.parse(instant), ZoneOffset.UTC)
    );
  }

  private static BudgetPolicy policy(String fallbackTargetId, ZoneId zoneId) {
    return new BudgetPolicy(
        "policy-a",
        "tenant",
        "tenant_id",
        fallbackTargetId,
        usd("100.00"),
        zoneId
    );
  }

  private static Cost usd(String amount) {
    return Cost.of(new BigDecimal(amount), USD);
  }

  private static BudgetKey key(String policyId, String targetId, String window) {
    return new BudgetKey(policyId, "tenant", targetId, BudgetWindow.parse(window));
  }
}
