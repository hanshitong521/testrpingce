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
            return applyUnderLock(paymentId, orderId, amountCents);
        }
    }

    private CallbackResult applyUnderLock(String paymentId, String orderId, long amountCents) {
        PaymentRecord tracked = store.payments.get(paymentId);
        if (tracked != null) {
            return tracked.sameRequest(orderId, amountCents)
                    ? CallbackResult.DUPLICATE
                    : CallbackResult.CONFLICT;
        }
        Order order = orders.get(orderId);
        if (order == null || amountCents != order.payable) {
            return CallbackResult.REJECTED;
        }
        if (order.pendingPayment()) {
            orders.markPaymentCapturePending(orderId, paymentId);
        }
        PaymentApplication application = orders.applyPayment(paymentId, orderId, amountCents);
        if (application == PaymentApplication.REJECTED) {
            orders.clearPaymentCapture(orderId, paymentId);
            return CallbackResult.REJECTED;
        }
        PaymentStatus status = application == PaymentApplication.ACCEPTED
                ? PaymentStatus.CAPTURED
                : PaymentStatus.REFUND_REQUIRED;
        store.payments.put(paymentId,
                new PaymentRecord(paymentId, orderId, amountCents, status));
        if (application == PaymentApplication.REFUND_REQUIRED) {
            store.event(EventTypes.PAYMENT_REFUND_REQUIRED, orderId, paymentId, amountCents);
            return CallbackResult.REFUND_REQUIRED;
        }
        return CallbackResult.ACCEPTED;
    }
}
