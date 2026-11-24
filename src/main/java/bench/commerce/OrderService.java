package bench.commerce;

import java.util.List;

/**
 * Order lifecycle: creation, cancellation and payment application.
 *
 * <p>Every state transition and its side effects (stock, coupon, audit event) happen together, so a
 * caller must hold the store lock. The order status is the single source of truth for which
 * transition is still allowed.
 */
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

    /** Manual cancellation. Only an order still awaiting payment may be cancelled. */
    boolean cancel(String orderId) {
        Order order = store.orders.get(orderId);
        return order != null && cancelPending(order);
    }

    private boolean cancelPending(Order order) {
        if (!order.isPendingPayment()) {
            return false;
        }
        if (!inventory.release(order.id)) {
            throw new IllegalStateException("order " + order.id + " has no live reservation");
        }
        coupons.release(order.couponCode, order.id);
        order.status = OrderStatus.CANCELLED;
        store.event(EventTypes.ORDER_CANCELLED, order.id, null, 0);
        return true;
    }

    /** Expiry shares the cancellation transition so stock and coupon are released exactly once. */
    boolean expire(Order order, long now) {
        return order.isPendingPayment() && now >= order.expiresAt && cancelPending(order);
    }

    /** Commits a verified payment for an order that is still awaiting payment. */
    void completePayment(Order order, String paymentId, long amountCents) {
        if (!order.isPendingPayment()) {
            throw new IllegalStateException("order " + order.id + " is not awaiting payment");
        }
        if (!inventory.commit(order.id)) {
            throw new IllegalStateException("order " + order.id + " has no live reservation");
        }
        coupons.consume(order.couponCode, order.id);
        order.status = OrderStatus.PAID;
        order.paid = amountCents;
        store.event(EventTypes.PAYMENT_SUCCEEDED, order.id, paymentId, amountCents);
    }

    Order get(String orderId) {
        return store.orders.get(orderId);
    }

    OrderView view(String orderId) {
        Order order = store.orders.get(orderId);
        return order == null ? null : order.view();
    }
}
