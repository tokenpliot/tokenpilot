package io.tokenpilot.budget;

import java.time.Clock;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.Objects;

/**
 * Monthly budget period in the configured time zone.
 *
 * @param value year and month to which the budget applies
 */
public record BudgetWindow(YearMonth value) {

  public BudgetWindow {
    Objects.requireNonNull(value, "value must not be null");
  }

  public static BudgetWindow resolve(Clock clock, ZoneId zoneId) {
    Objects.requireNonNull(clock, "clock must not be null");
    Objects.requireNonNull(zoneId, "zoneId must not be null");
    return new BudgetWindow(YearMonth.now(clock.withZone(zoneId)));
  }

  public static BudgetWindow parse(String value) {
    return new BudgetWindow(YearMonth.parse(value));
  }

  @Override
  public String toString() {
    return value.toString();
  }
}
