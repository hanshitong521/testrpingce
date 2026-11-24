package bench.commerce;

import java.util.ArrayList;
import java.util.List;

/** Expires orders whose deadline has passed. */
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
        List<String> due = new ArrayList<>();
        for (Order order : store.orders.values()) {
            if (order.status == OrderStatus.PENDING_PAYMENT && now >= order.expiresAt) {
                due.add(order.id);
            }
        }
        int count = 0;
        for (String orderId : due) {
            Order order = store.orders.get(orderId);
            // Re-check at commit time: the order may have been paid or cancelled
            // since the scan captured it. cancel() itself guards the status too.
            if (order != null && order.status == OrderStatus.PENDING_PAYMENT
                    && now >= order.expiresAt && orders.cancel(orderId)) {
                count++;
            }
        }
        return count;
    }
}
