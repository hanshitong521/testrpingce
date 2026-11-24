package bench.commerce;

/** Handles payment callbacks with idempotency and late-payment compensation. */
final class PaymentService {
    private final CommerceStore store;
    private final OrderService orders;

    PaymentService(CommerceStore store, OrderService orders) {
        this.store = store;
        this.orders = orders;
    }

    CallbackResult success(String paymentId, String orderId, long amountCents) {
        if (paymentId == null || paymentId.isBlank() || orderId == null || orderId.isBlank() || amountCents <= 0) {
            return CallbackResult.REJECTED;
        }
        PaymentRecord previous = store.payments.get(paymentId);
        if (previous != null) {
            return previous.orderId.equals(orderId) && previous.amount == amountCents
                    ? CallbackResult.DUPLICATE
                    : CallbackResult.CONFLICT;
        }
        Order order = orders.get(orderId);
        if (order == null) {
            return CallbackResult.REJECTED;
        }
        // An incorrect amount must not create an idempotency record, so the same
        // paymentId can be retried later with the valid payload.
        if (amountCents != order.payable) {
            return CallbackResult.REJECTED;
        }
        CallbackResult result = orders.applyPayment(order, paymentId, amountCents);
        PaymentStatus status = result == CallbackResult.ACCEPTED
                ? PaymentStatus.CAPTURED : PaymentStatus.REFUND_REQUIRED;
        store.payments.put(paymentId, new PaymentRecord(paymentId, orderId, amountCents, status));
        if (result == CallbackResult.REFUND_REQUIRED) {
            store.event("PAYMENT_REFUND_REQUIRED", orderId, paymentId, amountCents);
        }
        return result;
    }
}
