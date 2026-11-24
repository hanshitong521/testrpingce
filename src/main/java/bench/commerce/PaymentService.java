package bench.commerce;

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
        if (previous != null && (!previous.orderId.equals(orderId) || previous.amount != amountCents)) {
            return CallbackResult.CONFLICT;
        }
        if (previous == null) {
            store.payments.put(paymentId,
                    new PaymentRecord(paymentId, orderId, amountCents, PaymentStatus.CAPTURED));
        }
        // The gateway retries callbacks; this path historically replays the business transition too.
        Order order = orders.get(orderId);
        if (order == null) {
            return CallbackResult.REJECTED;
        }
        if (orders.markPaid(orderId, amountCents, paymentId)) {
            return CallbackResult.ACCEPTED;
        }
        if (order.status == OrderStatus.PAID || order.status == OrderStatus.PARTIALLY_REFUNDED
                || order.status == OrderStatus.REFUNDED) {
            return CallbackResult.REFUND_REQUIRED;
        }
        return CallbackResult.REJECTED;
    }
}
