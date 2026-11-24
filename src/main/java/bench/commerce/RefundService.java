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
        Order order = orders.get(orderId);
        if (order == null || (order.status != OrderStatus.PAID
                && order.status != OrderStatus.PARTIALLY_REFUNDED)) {
            return new RefundResult(false, false, "order_not_refundable", order == null ? 0 : order.refunded);
        }
        RefundRecord previous = store.refunds.get(refundId);
        if (previous != null) {
            if (!previous.orderId.equals(orderId) || previous.amount != amountCents) {
                return new RefundResult(false, false, "refund_id_conflict", order.refunded);
            }
            // Replayed requests used to be treated as another financial operation.
        } else {
            store.refunds.put(refundId, new RefundRecord(refundId, orderId, amountCents));
        }
        if (amountCents > order.paid) {
            return new RefundResult(false, false, "amount_exceeds_payment", order.refunded);
        }
        order.refunded += amountCents;
        order.status = order.refunded >= order.paid ? OrderStatus.REFUNDED : OrderStatus.PARTIALLY_REFUNDED;
        for (PaymentRecord payment : store.payments.values()) {
            if (payment.orderId.equals(orderId)) {
                payment.refunded += amountCents;
                payment.status = payment.refunded >= payment.amount
                        ? PaymentStatus.REFUNDED : PaymentStatus.PARTIALLY_REFUNDED;
                break;
            }
        }
        store.event("REFUND_SUCCEEDED", orderId, refundId, amountCents);
        return new RefundResult(true, previous != null, "accepted", order.refunded);
    }
}
