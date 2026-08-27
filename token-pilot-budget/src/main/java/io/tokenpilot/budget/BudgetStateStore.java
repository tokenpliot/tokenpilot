package io.tokenpilot.budget;

import io.tokenpilot.core.domain.Cost;

/**
 * Manages accumulated cost and limit/currency snapshots for each resolved
 * {@link BudgetKey}.
 */
public interface BudgetStateStore {

  /**
   * Legacy API for reading committed cost. Reserved amounts are not included.
   *
   * <p>New provider admission boundaries must use
   * {@link #snapshot(BudgetKey, Cost)} or
   * {@link #checkAndReserve(BudgetReservationRequest)}.</p>
   */
  Cost getAccumulatedCost(BudgetKey key, Cost limit);

  /**
   * Legacy API for adding committed cost. It does not change the reservation lifecycle.
   */
  void addCost(BudgetKey key, Cost limit, Cost amount);

  /**
   * Performs the read and safe upper-bound reservation as one atomic operation.
   *
   * <p>For source compatibility with existing implementations, the default
   * implementation explicitly returns that the operation is unsupported.
   * Implementations that support reservations must implement this method or the
   * request overload.</p>
   *
   * @param key budget bucket identifier
   * @param limit budget limit snapshot fixed for the bucket
   * @param safeUpperBoundCost conservative cost bound to reserve
   * @param idempotencyKey duplicate-request identifier
   * @return created, reused, blocked, conflict, or currency-mismatch result
   * @deprecated Use an overload or request object that separates request ID and idempotency key.
   */
  @Deprecated(since = "0.1.0", forRemoval = false)
  default BudgetReservationResult checkAndReserve(
      BudgetKey key,
      Cost limit,
      Cost safeUpperBoundCost,
      String idempotencyKey
  ) {
    return checkAndReserve(new BudgetReservationRequest(
        key,
        limit,
        safeUpperBoundCost,
        idempotencyKey
    ));
  }

  /**
   * Atomic reservation overload using a typed idempotency key.
   *
   * @deprecated Use an overload or request object that separates request ID and idempotency key.
   */
  @Deprecated(since = "0.1.0", forRemoval = false)
  default BudgetReservationResult checkAndReserve(
      BudgetKey key,
      Cost limit,
      Cost safeUpperBoundCost,
      IdempotencyKey idempotencyKey
  ) {
    return checkAndReserve(new BudgetReservationRequest(
        key,
        limit,
        safeUpperBoundCost,
        idempotencyKey,
        null,
        null,
        null
    ));
  }

  /**
   * Atomic reservation overload separating request correlation from the
   * duplicate-prevention identifier.
   */
  default BudgetReservationResult checkAndReserve(
      BudgetKey key,
      Cost limit,
      Cost safeUpperBoundCost,
      String requestId,
      IdempotencyKey idempotencyKey
  ) {
    return checkAndReserve(new BudgetReservationRequest(
        key,
        limit,
        safeUpperBoundCost,
        requestId,
        idempotencyKey,
        null,
        null,
        null,
        java.util.Optional.empty()
    ));
  }

  /**
   * Atomic reservation request including model and pricing snapshot metadata.
   */
  default BudgetReservationResult checkAndReserve(BudgetReservationRequest request) {
    throw new UnsupportedOperationException(
        "This BudgetStateStore does not support atomic reservations"
    );
  }

  /**
   * Read snapshot including reservations and unresolved reconciliation liability.
   * Legacy stores may return a compatible snapshot containing committed cost only.
   */
  default BudgetSnapshot snapshot(BudgetKey key, Cost limit) {
    Cost committedCost = getAccumulatedCost(key, limit);
    return new BudgetSnapshot(
        key,
        limit,
        committedCost,
        Cost.zero(limit.currency()),
        Cost.zero(limit.currency()),
        java.util.Set.of()
    );
  }

  /**
   * Explicit query alias for {@link #snapshot(BudgetKey, Cost)}.
   */
  default BudgetSnapshot getSnapshot(BudgetKey key, Cost limit) {
    return snapshot(key, limit);
  }
}
