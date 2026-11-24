# Legacy Commerce Engine

A small, dependency-free Java 17 commerce backend used for an agent engineering benchmark.

## Situation

This service has evolved through several rounds of maintenance. Recent support reports mention inconsistent order and stock state when payment callbacks, manual cancellation, and timeout expiration overlap. Duplicate callbacks and repeated customer actions have also produced suspicious financial and inventory records.

Normal checkout and payment usually work. The defects are intermittent or appear only after a sequence of operations. Some code is old and awkward, but not every awkward-looking behavior is wrong: preserve the documented business rules below.

## Runtime

- Java 17
- No network access or third-party dependencies required
- In-memory, single-process service
- `TimeSource` is injected; tests should not use `Thread.sleep`

Run the visible suite:

```bash
# Linux/macOS/Git Bash
bash run-tests.sh

# Windows PowerShell
powershell -ExecutionPolicy Bypass -File .\run-tests.ps1
```

The baseline suite is expected to expose defects before your changes. A failing baseline is diagnostic evidence, not a reason to weaken or delete tests.

## Public business rules

### Orders and inventory

1. Creating an order reserves every line item. It must be all-or-nothing: if any SKU lacks stock or the order cannot be created, no stock or coupon may remain reserved.
2. `available + reserved + sold` is the inventory total for a SKU. These buckets must never become negative.
3. A successful payment commits the reservation exactly once: reserved decreases and sold increases for every line.
4. Cancelling an unpaid order releases each reservation exactly once. Repeating cancellation must not change inventory again.
5. A paid order cannot be cancelled through the unpaid-order cancellation path.
6. A cancelled/expired order must never become paid later because a delayed callback arrived. The service cannot undo money captured by an external gateway, so a late successful payment must be recorded as `REFUND_REQUIRED`, with a `PAYMENT_REFUND_REQUIRED` audit event; it must not reacquire or commit stock.
7. Timeout expiration applies only when `now >= expiresAt` and the order is still pending payment at the moment the transition is committed. An expiration scan racing with payment must not leave paid orders with released stock or cancelled orders with committed stock.

### Payment callbacks

1. Re-delivery of the same `paymentId`, `orderId`, and amount is idempotent. It must not commit inventory or emit another successful-payment event.
2. Reuse of a `paymentId` with a different order or amount is a conflict and must not mutate either order.
3. A callback amount must exactly equal the order's payable amount. A mismatch must not change order, inventory, coupon, payment-idempotency records, or successful-payment events; the same paymentId may be retried later with the valid payload.
4. A second distinct successful payment for an already-paid order is not another sale. Record it as `REFUND_REQUIRED` and emit `PAYMENT_REFUND_REQUIRED`; do not commit inventory twice.

### Coupons

1. A coupon can be reserved by at most one live order.
2. Cancelling/expiring an unpaid order releases its reserved coupon once.
3. Successful payment consumes the coupon (`USED`). A later refund does not make a used coupon available again.
4. Failed order creation must not strand a coupon in `RESERVED`.

### Refunds

1. Refund amounts must be positive and cannot exceed the remaining refundable amount.
2. Multiple partial refunds are allowed; their cumulative amount cannot exceed the captured payment.
3. Repeating the same `refundId` with the same order and amount is idempotent.
4. Reusing a `refundId` with different details is a conflict.
5. A partial refund moves the order to `PARTIALLY_REFUNDED`; a full cumulative refund moves it to `REFUNDED`.
6. Refunds do not silently release or re-add inventory. Physical return/restock is outside this benchmark.

### Compatibility requirements

- Keep the public facade usable: `CommerceSystem` and its public methods are the supported entry point.
- Preserve the documented status meanings and inventory accounting.
- Keep deterministic behavior with an injected `TimeSource`.
- Do not add external dependencies or network services.
- Domain events are part of the observable audit trail. A successful transition should not create duplicate success events when the same request is retried.

## Your task

Read the codebase, run the baseline tests, investigate the reported inconsistencies, identify the underlying cause(s), and repair the system. You are expected to reason across modules, protect existing behavior, add meaningful tests, and improve code where it helps make the invariants easier to maintain.
