package bench.commerce;

/**
 * Refund bookkeeping against a captured payment.
 *
 * <p>Refunds never touch inventory. A refund id is idempotent: a replay reports the original result
 * without moving money again, while reusing the id for different details is a conflict.
 */
final class RefundService {
    private static final String ACCEPTED = "accepted";
    private static final String INVALID_REQUEST = "invalid_request";
    private static final String ORDER_NOT_REFUNDABLE = "order_not_refundable";
    private static final String REFUND_ID_CONFLICT = "refund_id_conflict";
    private static final String AMOUNT_EXCEEDS_PAYMENT = "amount_exceeds_payment";

    private final CommerceStore store;
    private final OrderService orders;

    RefundService(CommerceStore store, OrderService orders) {
        this.store = store;
        this.orders = orders;
    }

    RefundResult refund(String refundId, String orderId, long amountCents) {
        if (isBlank(refundId) || isBlank(orderId) || amountCents <= 0) {
            return new RefundResult(false, false, INVALID_REQUEST, 0);
        }
        Order order = orders.get(orderId);
        if (order == null) {
            return new RefundResult(false, false, ORDER_NOT_REFUNDABLE, 0);
        }
        RefundRecord previous = store.refunds.get(refundId);
        if (previous != null) {
            if (!previous.orderId.equals(orderId) || previous.amount != amountCents) {
                return new RefundResult(false, false, REFUND_ID_CONFLICT, order.refunded);
            }
            // Checked before the order state: replaying a full refund must stay idempotent.
            return new RefundResult(true, true, ACCEPTED, order.refunded);
        }
        if (!order.isRefundable()) {
            return new RefundResult(false, false, ORDER_NOT_REFUNDABLE, order.refunded);
        }
        if (amountCents > order.remainingRefundable()) {
            return new RefundResult(false, false, AMOUNT_EXCEEDS_PAYMENT, order.refunded);
        }
        store.refunds.put(refundId, new RefundRecord(refundId, orderId, amountCents));
        order.refunded += amountCents;
        order.status = order.remainingRefundable() == 0 ? OrderStatus.REFUNDED : OrderStatus.PARTIALLY_REFUNDED;
        applyToCapturedPayment(orderId, amountCents);
        store.event(EventTypes.REFUND_SUCCEEDED, orderId, refundId, amountCents);
        return new RefundResult(true, false, ACCEPTED, order.refunded);
    }

    private void applyToCapturedPayment(String orderId, long amountCents) {
        PaymentRecord payment = store.capturedPaymentFor(orderId);
        if (payment == null) {
            throw new IllegalStateException("order " + orderId + " has no captured payment to refund");
        }
        payment.refunded += amountCents;
        payment.status = payment.refunded >= payment.amount
                ? PaymentStatus.REFUNDED : PaymentStatus.PARTIALLY_REFUNDED;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
