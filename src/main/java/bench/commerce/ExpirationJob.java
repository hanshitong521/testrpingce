package bench.commerce;

import java.util.ArrayList;
import java.util.List;

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
        synchronized (store.mutex) {
            for (Order order : store.orders.values()) {
                if (order.status == OrderStatus.PENDING_PAYMENT && now >= order.expiresAt) {
                    due.add(order.id);
                }
            }
        }
        int count = 0;
        for (String orderId : due) {
            if (orders.expire(orderId)) {
                count++;
            }
        }
        return count;
    }
}
