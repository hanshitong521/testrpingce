package bench.commerce;

import java.util.List;

/**
 * Public entry point for the legacy commerce application.
 *
 * <p>Every method delegates inside a single store transaction, so each call is one atomic business
 * operation: state checks, stock moves, coupon moves and audit events share a commit boundary.
 */
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
        store.transaction(() -> inventory.addStock(sku, quantity));
    }

    public int availableStock(String sku) {
        return store.transaction(() -> inventory.available(sku));
    }

    public int reservedStock(String sku) {
        return store.transaction(() -> inventory.reserved(sku));
    }

    public int soldStock(String sku) {
        return store.transaction(() -> inventory.sold(sku));
    }

    public void addCoupon(String code, long discountCents) {
        store.transaction(() -> coupons.addCoupon(code, discountCents));
    }

    public CouponStatus couponStatus(String code) {
        return store.transaction(() -> coupons.status(code));
    }

    public OrderView createOrder(String orderId, List<OrderItem> items, String couponCode, long ttlMillis) {
        return store.transaction(() -> orders.create(orderId, items, couponCode, ttlMillis));
    }

    public boolean cancelOrder(String orderId) {
        return store.transaction(() -> orders.cancel(orderId));
    }

    public CallbackResult paymentSucceeded(String paymentId, String orderId, long amountCents) {
        return store.transaction(() -> payments.success(paymentId, orderId, amountCents));
    }

    public int expireDueOrders() {
        return store.transaction(() -> expiration.run());
    }

    public RefundResult refund(String refundId, String orderId, long amountCents) {
        return store.transaction(() -> refunds.refund(refundId, orderId, amountCents));
    }

    public OrderView order(String orderId) {
        return store.transaction(() -> orders.view(orderId));
    }

    public List<DomainEvent> events() {
        return store.transaction(() -> List.copyOf(store.events));
    }
}
