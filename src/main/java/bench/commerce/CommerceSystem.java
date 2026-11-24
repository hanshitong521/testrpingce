package bench.commerce;

import java.util.List;

/** Public entry point for the legacy commerce application. */
public final class CommerceSystem {
    private final CommerceStore store = new CommerceStore();
    private final InventoryService inventory = new InventoryService(store);
    private final CouponService coupons = new CouponService(store);
    private final OrderService orders;
    private final PaymentService payments;
    private final ExpirationJob expiration;
    private final RefundService refunds;

    public CommerceSystem(TimeSource clock) {
        if (clock == null) {
            throw new IllegalArgumentException("clock is required");
        }
        orders = new OrderService(store, inventory, coupons, clock);
        payments = new PaymentService(store, orders);
        expiration = new ExpirationJob(store, orders, clock);
        refunds = new RefundService(store, orders);
    }

    public void addStock(String sku, int quantity) {
        synchronized (store.mutex) {
            inventory.addStock(sku, quantity);
        }
    }

    public int availableStock(String sku) {
        return inventory.available(sku);
    }

    public int reservedStock(String sku) {
        return inventory.reserved(sku);
    }

    public int soldStock(String sku) {
        return inventory.sold(sku);
    }

    public void addCoupon(String code, long discountCents) {
        synchronized (store.mutex) {
            coupons.addCoupon(code, discountCents);
        }
    }

    public CouponStatus couponStatus(String code) {
        return coupons.status(code);
    }

    public OrderView createOrder(String orderId, List<OrderItem> items, String couponCode, long ttlMillis) {
        synchronized (store.mutex) {
            return orders.create(orderId, items, couponCode, ttlMillis);
        }
    }

    public boolean cancelOrder(String orderId) {
        synchronized (store.mutex) {
            return orders.cancel(orderId);
        }
    }

    public CallbackResult paymentSucceeded(String paymentId, String orderId, long amountCents) {
        return payments.success(paymentId, orderId, amountCents);
    }

    public int expireDueOrders() {
        synchronized (store.mutex) {
            return expiration.run();
        }
    }

    public RefundResult refund(String refundId, String orderId, long amountCents) {
        return refunds.refund(refundId, orderId, amountCents);
    }

    public OrderView order(String orderId) {
        return orders.view(orderId);
    }

    public List<DomainEvent> events() {
        return List.copyOf(store.events);
    }
}
