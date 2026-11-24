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
        store.event("ORDER_CREATED", orderId, null, order.payable);
        return order.view();
    }

    boolean cancel(String orderId) {
        Order order = store.orders.get(orderId);
        if (order == null || order.status != OrderStatus.PENDING_PAYMENT) {
            return false;
        }
        inventory.release(orderId);
        coupons.release(order.couponCode, orderId);
        order.status = OrderStatus.CANCELLED;
        store.event("ORDER_CANCELLED", orderId, null, 0);
        return true;
    }

    boolean markPaid(String orderId, long amountCents, String paymentId) {
        Order order = store.orders.get(orderId);
        if (order == null || order.status == OrderStatus.PAID
                || order.status == OrderStatus.REFUNDED
                || order.status == OrderStatus.PARTIALLY_REFUNDED) {
            return false;
        }
        if (amountCents != order.payable) {
            return false;
        }
        inventory.commit(orderId);
        coupons.consume(order.couponCode, orderId);
        order.status = OrderStatus.PAID;
        order.paid = amountCents;
        store.event("PAYMENT_SUCCEEDED", orderId, paymentId, amountCents);
        return true;
    }

    Order get(String orderId) {
        return store.orders.get(orderId);
    }

    OrderView view(String orderId) {
        Order order = store.orders.get(orderId);
        return order == null ? null : order.view();
    }
}
