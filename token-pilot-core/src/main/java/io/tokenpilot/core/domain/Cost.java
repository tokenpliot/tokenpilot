package io.tokenpilot.core.domain;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.Objects;

/**
 * Calculated AI call cost.
 *
 * Preserves internal calculation precision; rounding for display or billing is
 * applied at an external boundary.
 *
 * @param value    non-negative cost
 * @param currency cost currency
 */
public record Cost(
        BigDecimal value,
        Currency currency
) {
    public Cost {
        Objects.requireNonNull(value, "value must not be null");
        Objects.requireNonNull(currency, "currency must not be null");
        if (value.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("Cost value must not be negative");
        }
    }

    public static Cost of(BigDecimal value, Currency currency) {
        return new Cost(value, currency);
    }

    public static Cost zero(Currency currency) {
        return of(BigDecimal.ZERO, currency);
    }

    public Cost add(Cost other) {
        Objects.requireNonNull(other, "other must not be null");
        validateSameCurrency(other);
        return new Cost(value.add(other.value), currency);
    }

    public int compareTo(Cost other) {
        Objects.requireNonNull(other, "other must not be null");
        validateSameCurrency(other);
        return value.compareTo(other.value);
    }

    private void validateSameCurrency(Cost other) {
        if (!currency.equals(other.currency)) {
            throw new IllegalArgumentException("Cannot operate on costs with different currencies");
        }
    }

    @Override
    public boolean equals(Object object) {
        if (this == object) {
            return true;
        }
        if (!(object instanceof Cost other)) {
            return false;
        }
        return value.compareTo(other.value) == 0 && currency.equals(other.currency);
    }

    @Override
    public int hashCode() {
        return Objects.hash(value.stripTrailingZeros(), currency);
    }
}
