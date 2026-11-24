package bench.commerce;

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
        synchronized (store.mutex) {
            RefundRecord previous = store.refunds.get(refundId);
            if (previous != null && !previous.sameRequest(orderId, amountCents)) {
                return new RefundResult(false, false, "refund_id_conflict", refundTotal(orderId));
            }
            Order order = orders.get(orderId);
            if (order == null || (order.status != OrderStatus.PAID
                    && order.status != OrderStatus.PARTIALLY_REFUNDED)) {
                return new RefundResult(false, false, "order_not_refundable", refundTotal(orderId));
            }
            if (previous != null) {
                return new RefundResult(true, true, "accepted", order.refunded);
            }
            if (amountCents > order.remainingRefundable()) {
                return new RefundResult(false, false, "amount_exceeds_payment", order.refunded);
            }
            order.refunded += amountCents;
            order.status = order.remainingRefundable() == 0
                    ? OrderStatus.REFUNDED : OrderStatus.PARTIALLY_REFUNDED;
            PaymentRecord payment = capturedPayment(order);
            if (payment != null && payment.refundable()) {
                payment.applyRefund(amountCents);
            }
            store.refunds.put(refundId, new RefundRecord(refundId, orderId, amountCents));
            store.event(EventTypes.REFUND_SUCCEEDED, orderId, refundId, amountCents);
            return new RefundResult(true, false, "accepted", order.refunded);
        }
    }

    private long refundTotal(String orderId) {
        Order order = orders.get(orderId);
        return order == null ? 0 : order.refunded;
    }

    private PaymentRecord capturedPayment(Order order) {
        PaymentRecord linked = store.payments.get(order.capturingPaymentId);
        if (linked != null && linked.orderId.equals(order.id) && linked.capturesOrder()) {
            return linked;
        }
        for (PaymentRecord payment : store.payments.values()) {
            if (payment.orderId.equals(order.id) && payment.capturesOrder()) {
                return payment;
            }
        }
        return null;
    }
}
