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
            return applyUnderLock(refundId, orderId, amountCents);
        }
    }

    private RefundResult applyUnderLock(String refundId, String orderId, long amountCents) {
        RefundRecord previous = store.refunds.get(refundId);
        if (previous != null) {
            return previous.sameRequest(orderId, amountCents)
                    ? new RefundResult(true, true, "accepted", refundedFor(orderId))
                    : new RefundResult(false, false, "refund_id_conflict", refundedFor(orderId));
        }
        Order order = orders.get(orderId);
        if (order == null || !order.paidOrRefunded() || order.status == OrderStatus.REFUNDED) {
            return new RefundResult(false, false, "order_not_refundable", order == null ? 0 : order.refunded);
        }
        if (amountCents > order.remainingRefundable()) {
            return new RefundResult(false, false, "amount_exceeds_payment", order.refunded);
        }
        PaymentRecord payment = findRefundablePayment(orderId);
        if (payment == null || amountCents > payment.amount - payment.refunded) {
            return new RefundResult(false, false, "amount_exceeds_payment", order.refunded);
        }
        order.refunded += amountCents;
        order.status = order.remainingRefundable() == 0
                ? OrderStatus.REFUNDED
                : OrderStatus.PARTIALLY_REFUNDED;
        payment.applyRefund(amountCents);
        store.refunds.put(refundId, new RefundRecord(refundId, orderId, amountCents));
        store.event(EventTypes.REFUND_SUCCEEDED, orderId, refundId, amountCents);
        return new RefundResult(true, false, "accepted", order.refunded);
    }

    private PaymentRecord findRefundablePayment(String orderId) {
        for (PaymentRecord payment : store.payments.values()) {
            if (payment.orderId.equals(orderId) && payment.refundable()
                    && payment.amount > payment.refunded) {
                return payment;
            }
        }
        return null;
    }

    private long refundedFor(String orderId) {
        Order order = orders.get(orderId);
        return order == null ? 0 : order.refunded;
    }
}
