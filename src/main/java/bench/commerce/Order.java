package bench.commerce;

import java.util.ArrayList;
import java.util.List;

final class Order {
    final String id;
    final List<OrderItem> items;
    final long subtotal;
    final long discount;
    final long payable;
    final String couponCode;
    final long createdAt;
    final long expiresAt;
    OrderStatus status = OrderStatus.PENDING_PAYMENT;
    long paid;
    long refunded;
    String capturingPaymentId;

    Order(String id, List<OrderItem> items, long subtotal, long discount, String couponCode,
          long createdAt, long expiresAt) {
        this.id = id;
        this.items = new ArrayList<>(items);
        this.subtotal = subtotal;
        this.discount = discount;
        this.payable = subtotal - discount;
        this.couponCode = couponCode;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    OrderView view() {
        return new OrderView(id, items, subtotal, discount, payable, couponCode, status,
                paid, refunded, createdAt, expiresAt);
    }

    boolean pendingPayment() {
        return status == OrderStatus.PENDING_PAYMENT;
    }

    long remainingRefundable() {
        return paid - refunded;
    }
}
