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
        synchronized (store.mutex) {
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
    }

    boolean cancel(String orderId) {
        synchronized (store.mutex) {
            Order order = store.orders.get(orderId);
            if (order == null || !order.pendingPayment()) {
                return false;
            }
            order.status = OrderStatus.CANCELLED;
            inventory.release(orderId);
            coupons.release(order.couponCode, orderId);
            store.event(EventTypes.ORDER_CANCELLED, orderId, null, 0);
            return true;
        }
    }

    boolean commitPayment(String orderId, long amountCents, String paymentId) {
        synchronized (store.mutex) {
            Order order = store.orders.get(orderId);
            if (order == null || !order.pendingPayment() || amountCents != order.payable) {
                return false;
            }
            inventory.commit(orderId);
            coupons.consume(order.couponCode, orderId);
            order.status = OrderStatus.PAID;
            order.paid = amountCents;
            order.capturingPaymentId = paymentId;
            store.event(EventTypes.PAYMENT_SUCCEEDED, orderId, paymentId, amountCents);
            return true;
        }
    }

    boolean hasRefund(String orderId) {
        synchronized (store.mutex) {
            Order order = store.orders.get(orderId);
            return order != null && order.refunded > 0;
        }
    }

    Order get(String orderId) {
        synchronized (store.mutex) {
            return store.orders.get(orderId);
        }
    }

    OrderView view(String orderId) {
        synchronized (store.mutex) {
            Order order = store.orders.get(orderId);
            return order == null ? null : order.view();
        }
    }
}
