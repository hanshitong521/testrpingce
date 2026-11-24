package bench.commerce;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class CommerceStore {
    final Object mutex = new Object();
    final Map<String, Order> orders = new LinkedHashMap<>();
    final Map<String, InventoryRecord> inventory = new LinkedHashMap<>();
    final Map<String, Reservation> reservations = new LinkedHashMap<>();
    final Map<String, Coupon> coupons = new LinkedHashMap<>();
    final Map<String, PaymentRecord> payments = new LinkedHashMap<>();
    final Map<String, RefundRecord> refunds = new LinkedHashMap<>();
    final List<DomainEvent> events = new ArrayList<>();
    long eventSequence;

    void event(String type, String orderId, String reference, long amount) {
        events.add(new DomainEvent(++eventSequence, type, orderId, reference, amount));
    }
}
