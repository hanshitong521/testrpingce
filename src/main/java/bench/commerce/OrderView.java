package bench.commerce;

import java.util.List;

public record OrderView(
        String orderId,
        List<OrderItem> items,
        long subtotalCents,
        long discountCents,
        long payableCents,
        String couponCode,
        OrderStatus status,
        long paidCents,
        long refundedCents,
        long createdAtMillis,
        long expiresAtMillis) {
    public OrderView {
        items = List.copyOf(items);
    }
}
