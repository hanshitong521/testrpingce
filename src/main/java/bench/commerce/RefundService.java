package bench.commerce;

/** Handles refunds with idempotency, conflict detection and a cumulative cap. */
final class RefundService {
    private final CommerceStore store;
    private final OrderService orders;

    RefundService(CommerceStore store, OrderService orders) {
        this.store = store;
        this.orders = orders;
    }

    RefundResult refund(String refundId, String orderId, long amountCents) {
        if (refundId == null || refundId.isBlank() || orderId == null || orderId.isBlank() || amountCents <= 0) {
            return new RefundResult(false, false, "invalid_request", 0);
        }
        RefundRecord previous = store.refunds.get(refundId);
        if (previous != null) {
            if (!previous.orderId.equals(orderId) || previous.amount != amountCents) {
                return new RefundResult(false, false, "refund_id_conflict", totalRefunded(orderId));
            }
            return new RefundResult(true, true, "accepted", totalRefunded(orderId));
        }
        Order order = orders.get(orderId);
        if (order == null || (order.status != OrderStatus.PAID
                && order.status != OrderStatus.PARTIALLY_REFUNDED)) {
            return new RefundResult(false, false, "order_not_refundable", order == null ? 0 : order.refunded);
        }
        if (amountCents > order.paid - order.refunded) {
            return new RefundResult(false, false, "amount_exceeds_payment", order.refunded);
        }
        store.refunds.put(refundId, new RefundRecord(refundId, orderId, amountCents));
        order.refunded += amountCents;
        order.status = order.refunded >= order.paid ? OrderStatus.REFUNDED : OrderStatus.PARTIALLY_REFUNDED;
        PaymentRecord capture = capturedPayment(orderId);
        if (capture != null) {
            capture.refunded += amountCents;
            capture.status = capture.refunded >= capture.amount
                    ? PaymentStatus.REFUNDED : PaymentStatus.PARTIALLY_REFUNDED;
        }
        store.event("REFUND_SUCCEEDED", orderId, refundId, amountCents);
        return new RefundResult(true, false, "accepted", order.refunded);
    }

    private long totalRefunded(String orderId) {
        Order order = orders.get(orderId);
        return order == null ? 0 : order.refunded;
    }

    private PaymentRecord capturedPayment(String orderId) {
        for (PaymentRecord payment : store.payments.values()) {
            if (payment.orderId.equals(orderId) && payment.status != PaymentStatus.REFUND_REQUIRED) {
                return payment;
            }
        }
        return null;
    }
}
