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
        synchronized (store.mutex) {
            PaymentRecord previous = store.payments.get(paymentId);
            if (previous != null) {
                return replayResult(previous, orderId, amountCents);
            }
            Order order = orders.get(orderId);
            if (order == null) {
                return CallbackResult.REJECTED;
            }
            if (amountCents != order.payable) {
                return CallbackResult.REJECTED;
            }
            if (order.pendingPayment()) {
                if (!orders.commitPayment(orderId, amountCents, paymentId)) {
                    return CallbackResult.REJECTED;
                }
                store.payments.put(paymentId,
                        new PaymentRecord(paymentId, orderId, amountCents, PaymentStatus.CAPTURED));
                return CallbackResult.ACCEPTED;
            }
            // The gateway captured money after the order left the payable state. Money cannot be
            // reversed here, so record the compensation instead of touching stock or the order.
            recordRefundRequired(paymentId, orderId, amountCents);
            return CallbackResult.REFUND_REQUIRED;
        }
    }

    private CallbackResult replayResult(PaymentRecord previous, String orderId, long amountCents) {
        if (!previous.sameRequest(orderId, amountCents)) {
            return CallbackResult.CONFLICT;
        }
        if (previous.status == PaymentStatus.REFUND_REQUIRED) {
            return CallbackResult.REFUND_REQUIRED;
        }
        if (orders.hasRefund(orderId)) {
            recordRefundRequired(previous.paymentId, orderId, amountCents);
            return CallbackResult.REFUND_REQUIRED;
        }
        return CallbackResult.DUPLICATE;
    }

    private void recordRefundRequired(String paymentId, String orderId, long amountCents) {
        store.payments.put(paymentId,
                new PaymentRecord(paymentId, orderId, amountCents, PaymentStatus.REFUND_REQUIRED));
        store.event(EventTypes.PAYMENT_REFUND_REQUIRED, orderId, paymentId, amountCents);
    }
}
