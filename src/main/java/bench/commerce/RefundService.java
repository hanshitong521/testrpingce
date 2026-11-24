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
            return rejected("invalid_request", 0);
        }
        synchronized (store.mutex) {
            Order order = orders.get(orderId);
            if (order == null) {
                return rejected("order_not_refundable", 0);
            }
            if (order.status != OrderStatus.PAID && order.status != OrderStatus.PARTIALLY_REFUNDED) {
                return rejected("order_not_refundable", order.refunded);
            }

            RefundRecord previous = store.refunds.get(refundId);
            if (previous != null) {
                if (!previous.orderId.equals(orderId) || previous.amount != amountCents) {
                    return rejected("refund_id_conflict", order.refunded);
                }
                return new RefundResult(true, true, "accepted", order.refunded);
            }
            if (amountCents > order.paid - order.refunded) {
                return rejected("amount_exceeds_payment", order.refunded);
            }

            store.refunds.put(refundId, new RefundRecord(refundId, orderId, amountCents));
            order.refunded += amountCents;
            order.status = order.refunded == order.paid
                    ? OrderStatus.REFUNDED : OrderStatus.PARTIALLY_REFUNDED;
            PaymentRecord payment = paymentFor(orderId);
            if (payment != null) {
                payment.refunded += amountCents;
                payment.status = payment.refunded >= payment.amount
                        ? PaymentStatus.REFUNDED : PaymentStatus.PARTIALLY_REFUNDED;
            }
            store.event("REFUND_SUCCEEDED", orderId, refundId, amountCents);
            return new RefundResult(true, false, "accepted", order.refunded);
        }
    }

    private PaymentRecord paymentFor(String orderId) {
        for (PaymentRecord payment : store.payments.values()) {
            if (payment.orderId.equals(orderId) && payment.status == PaymentStatus.CAPTURED) {
                return payment;
            }
        }
        return null;
    }

    private static RefundResult rejected(String reason, long totalRefunded) {
        return new RefundResult(false, false, reason, totalRefunded);
    }
}
