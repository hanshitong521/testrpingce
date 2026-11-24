package bench.commerce;

import java.util.List;

final class OrderService {
    private final CommerceStore store;
    private final InventoryService inventory;
    private final CouponService coupons;
    private final TimeSource clock;

    OrderService(CommerceStore store, InventoryService inventory, CouponService coupons, TimeSource clock) {
        this.store = store;
        this.inventory = inventory;
        this.coupons = coupons;
        this.clock = clock;
    }

    OrderView create(String orderId, List<OrderItem> items, String couponCode, long ttlMillis) {
        if (orderId == null || orderId.isBlank() || items == null || items.isEmpty() || ttlMillis <= 0) {
            throw new IllegalArgumentException("invalid order request");
        }
        if (store.orders.containsKey(orderId)) {
            throw new IllegalArgumentException("order already exists");
        }
        long subtotal = 0;
        for (OrderItem item : items) {
            subtotal = Math.addExact(subtotal, item.lineTotalCents());
        }
        long discount = coupons.reserve(couponCode, orderId, subtotal);
        if (!inventory.reserve(orderId, items)) {
            coupons.release(couponCode, orderId);
            throw new IllegalStateException("insufficient inventory");
        }
        long now = clock.nowMillis();
        long expiresAt = ttlMillis > Long.MAX_VALUE - now ? Long.MAX_VALUE : now + ttlMillis;
        Order order = new Order(orderId, items, subtotal, discount, couponCode, now, expiresAt);
        store.orders.put(orderId, order);
        store.event(EventTypes.ORDER_CREATED, orderId, null, order.payable);
        return order.view();
    }

    boolean cancel(String orderId) {
        Order order = store.orders.get(orderId);
        if (order == null || !order.pendingPayment()) {
            return false;
        }
        inventory.release(orderId);
        coupons.release(order.couponCode, orderId);
        order.status = OrderStatus.CANCELLED;
        store.event(EventTypes.ORDER_CANCELLED, orderId, null, 0);
        return true;
    }

    PaymentApplication applyPayment(String paymentId, String orderId, long amountCents) {
        Order order = store.orders.get(orderId);
        if (order == null) {
            return PaymentApplication.REJECTED;
        }
        if (amountCents != order.payable) {
            return PaymentApplication.REJECTED;
        }
        if (order.paidOrRefunded()) {
            return PaymentApplication.REFUND_REQUIRED;
        }
        if (order.capturingPaymentId != null && !order.capturingPaymentId.equals(paymentId)) {
            return PaymentApplication.REFUND_REQUIRED;
        }
        if (!order.pendingPayment()) {
            return PaymentApplication.REFUND_REQUIRED;
        }
        inventory.commit(orderId);
        coupons.consume(order.couponCode, orderId);
        order.status = OrderStatus.PAID;
        order.paid = amountCents;
        store.event(EventTypes.PAYMENT_SUCCEEDED, orderId, paymentId, amountCents);
        return PaymentApplication.ACCEPTED;
    }

    void markPaymentCapturePending(String orderId, String paymentId) {
        Order order = store.orders.get(orderId);
        if (order != null) {
            order.capturingPaymentId = paymentId;
        }
    }

    void clearPaymentCapture(String orderId, String paymentId) {
        Order order = store.orders.get(orderId);
        if (order != null && paymentId.equals(order.capturingPaymentId)) {
            order.capturingPaymentId = null;
        }
    }

    Order get(String orderId) {
        return store.orders.get(orderId);
    }

    OrderView view(String orderId) {
        Order order = store.orders.get(orderId);
        return order == null ? null : order.view();
    }
}
