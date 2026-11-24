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
            store.event("ORDER_CREATED", orderId, null, order.payable);
            return order.view();
        }
    }

    boolean cancel(String orderId) {
        synchronized (store.mutex) {
            Order order = store.orders.get(orderId);
            if (order == null || order.status != OrderStatus.PENDING_PAYMENT) {
                return false;
            }
            cancelPending(order);
            return true;
        }
    }

    boolean expire(String orderId) {
        synchronized (store.mutex) {
            Order order = store.orders.get(orderId);
            if (order == null || order.status != OrderStatus.PENDING_PAYMENT
                    || clock.nowMillis() < order.expiresAt) {
                return false;
            }
            cancelPending(order);
            return true;
        }
    }

    private void cancelPending(Order order) {
        inventory.release(order.id);
        coupons.release(order.couponCode, order.id);
        order.status = OrderStatus.CANCELLED;
        store.event("ORDER_CANCELLED", order.id, null, 0);
    }

    CallbackTransition markPaid(String orderId, long amountCents, PaymentRecord payment) {
        synchronized (store.mutex) {
            Order order = store.orders.get(orderId);
            if (order == null) {
                return CallbackTransition.REJECTED;
            }
            if (payment.status == PaymentStatus.CAPTURED) {
                return CallbackTransition.DUPLICATE;
            }
            if (amountCents != order.payable) {
                return CallbackTransition.REJECTED;
            }
            if (order.status == OrderStatus.PENDING_PAYMENT) {
                inventory.commit(orderId);
                coupons.consume(order.couponCode, orderId);
                order.status = OrderStatus.PAID;
                order.paid = amountCents;
                payment.status = PaymentStatus.CAPTURED;
                store.event("PAYMENT_SUCCEEDED", orderId, payment.paymentId, amountCents);
                return CallbackTransition.ACCEPTED;
            }
            requireRefund(payment, "captured after order left pending payment");
            return CallbackTransition.REFUND_REQUIRED;
        }
    }

    /**
     * A delayed capture cannot be undone here; keep the audit record and compensation marker
     * without touching order, inventory, coupon or payment-idempotency state.
     */
    private void requireRefund(PaymentRecord payment, String reason) {
        payment.status = PaymentStatus.REFUND_REQUIRED;
        store.event("PAYMENT_REFUND_REQUIRED", payment.orderId, payment.paymentId, payment.amount);
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

    enum CallbackTransition {
        ACCEPTED,
        DUPLICATE,
        REJECTED,
        REFUND_REQUIRED
    }
}
