package bench.commerce;

import java.util.ArrayList;
import java.util.List;

/**
 * Expiry scan. The due-time check and the cancellation commit happen under one lock, so an order that
 * is being paid at the same moment can only end up in one of the two states.
 */
final class ExpirationJob {
    private final CommerceStore store;
    private final OrderService orders;
    private final TimeSource clock;

    ExpirationJob(CommerceStore store, OrderService orders, TimeSource clock) {
        this.store = store;
        this.orders = orders;
        this.clock = clock;
    }

    int run() {
        long now = clock.nowMillis();
        List<String> candidates = new ArrayList<>();
        for (Order order : store.orders.values()) {
            if (order.isPendingPayment() && now >= order.expiresAt) {
                candidates.add(order.id);
            }
        }
        int expired = 0;
        for (String orderId : candidates) {
            if (orders.expire(store.orders.get(orderId), now)) {
                expired++;
            }
        }
        return expired;
    }
}
