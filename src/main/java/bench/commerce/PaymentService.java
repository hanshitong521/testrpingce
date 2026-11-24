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
            PaymentRecord payment = store.payments.get(paymentId);
            if (payment == null) {
                Order order = orders.get(orderId);
                if (order == null || amountCents != order.payable) {
                    return CallbackResult.REJECTED;
                }
                // A rejected amount must not occupy the idempotency key: the gateway may retry it.
                payment = new PaymentRecord(paymentId, orderId, amountCents, PaymentStatus.PENDING);
                store.payments.put(paymentId, payment);
            } else if (!payment.orderId.equals(orderId) || payment.amount != amountCents) {
                return CallbackResult.CONFLICT;
            }

            switch (orders.markPaid(orderId, amountCents, payment)) {
                case ACCEPTED:
                    return CallbackResult.ACCEPTED;
                case DUPLICATE:
                    return CallbackResult.DUPLICATE;
                case REFUND_REQUIRED:
                    return CallbackResult.REFUND_REQUIRED;
                default:
                    return CallbackResult.REJECTED;
            }
        }
    }
}
