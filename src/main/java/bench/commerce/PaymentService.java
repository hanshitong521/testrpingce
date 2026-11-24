package bench.commerce;

/**
 * Payment callback handling.
 *
 * <p>Callbacks are retried by the gateway, so the same {@code paymentId} must never repeat a business
 * transition: the recorded payment is checked before any order or inventory change. A capture that
 * arrives for an order which already has the money (paid, cancelled or expired) cannot be applied to
 * stock and is recorded as needing compensation instead.
 */
final class PaymentService {
    private final CommerceStore store;
    private final OrderService orders;

    PaymentService(CommerceStore store, OrderService orders) {
        this.store = store;
        this.orders = orders;
    }

    CallbackResult success(String paymentId, String orderId, long amountCents) {
        if (isBlank(paymentId) || isBlank(orderId) || amountCents <= 0) {
            return CallbackResult.REJECTED;
        }
        PaymentRecord previous = store.payments.get(paymentId);
        if (previous != null) {
            if (!previous.orderId.equals(orderId) || previous.amount != amountCents) {
                return CallbackResult.CONFLICT;
            }
            return replayOf(previous);
        }
        Order order = orders.get(orderId);
        if (order == null || amountCents != order.payable) {
            // Nothing is recorded, so the gateway may retry the same paymentId with a valid payload.
            return CallbackResult.REJECTED;
        }
        if (order.isPendingPayment()) {
            orders.completePayment(order, paymentId, amountCents);
            store.payments.put(paymentId,
                    new PaymentRecord(paymentId, orderId, amountCents, PaymentStatus.CAPTURED));
            return CallbackResult.ACCEPTED;
        }
        recordRefundRequired(paymentId, order, amountCents);
        return CallbackResult.REFUND_REQUIRED;
    }

    private static CallbackResult replayOf(PaymentRecord previous) {
        return previous.status == PaymentStatus.REFUND_REQUIRED
                ? CallbackResult.REFUND_REQUIRED
                : CallbackResult.DUPLICATE;
    }

    /**
     * Money left the customer but the order no longer needs it: the external gateway cannot be
     * un-charged from here, so the capture is parked for compensation and never touches stock.
     */
    private void recordRefundRequired(String paymentId, Order order, long amountCents) {
        store.payments.put(paymentId,
                new PaymentRecord(paymentId, order.id, amountCents, PaymentStatus.REFUND_REQUIRED));
        store.event(EventTypes.PAYMENT_REFUND_REQUIRED, order.id, paymentId, amountCents);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
