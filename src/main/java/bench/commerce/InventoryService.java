package bench.commerce;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Stock reservations. A reservation is all-or-nothing per order and moves stock exactly once: only a
 * still-{@code RESERVED} reservation can be released or committed.
 */
final class InventoryService {
    private final CommerceStore store;

    InventoryService(CommerceStore store) {
        this.store = store;
    }

    void addStock(String sku, int quantity) {
        if (sku == null || sku.isBlank() || quantity <= 0) {
            throw new IllegalArgumentException("invalid stock adjustment");
        }
        InventoryRecord item = store.inventory.computeIfAbsent(sku, ignored -> new InventoryRecord(0));
        item.available = Math.addExact(item.available, quantity);
    }

    /**
     * Reserves every line of the order, or nothing at all.
     *
     * @return {@code false} when a SKU is unknown or lacks stock; inventory is untouched then
     */
    boolean reserve(String orderId, List<OrderItem> items) {
        if (store.reservations.containsKey(orderId)) {
            return false;
        }
        Map<String, Integer> requested = requestedQuantities(items);
        for (Map.Entry<String, Integer> line : requested.entrySet()) {
            InventoryRecord item = store.inventory.get(line.getKey());
            if (item == null || item.available < line.getValue()) {
                return false;
            }
        }
        for (Map.Entry<String, Integer> line : requested.entrySet()) {
            store.inventory.get(line.getKey()).reserve(line.getValue());
        }
        store.reservations.put(orderId, new Reservation(requested));
        return true;
    }

    /** @return {@code false} when the reservation was already released or committed */
    boolean release(String orderId) {
        Reservation reservation = store.reservations.get(orderId);
        if (reservation == null || !reservation.isReserved()) {
            return false;
        }
        for (Map.Entry<String, Integer> line : reservation.quantities.entrySet()) {
            store.inventory.get(line.getKey()).release(line.getValue());
        }
        reservation.state = ReservationState.RELEASED;
        return true;
    }

    /** @return {@code false} when the reservation was already released or committed */
    boolean commit(String orderId) {
        Reservation reservation = store.reservations.get(orderId);
        if (reservation == null || !reservation.isReserved()) {
            return false;
        }
        for (Map.Entry<String, Integer> line : reservation.quantities.entrySet()) {
            store.inventory.get(line.getKey()).commit(line.getValue());
        }
        reservation.state = ReservationState.COMMITTED;
        return true;
    }

    int available(String sku) {
        InventoryRecord item = store.inventory.get(sku);
        return item == null ? 0 : item.available;
    }

    int reserved(String sku) {
        InventoryRecord item = store.inventory.get(sku);
        return item == null ? 0 : item.reserved;
    }

    int sold(String sku) {
        InventoryRecord item = store.inventory.get(sku);
        return item == null ? 0 : item.sold;
    }

    private static Map<String, Integer> requestedQuantities(List<OrderItem> items) {
        Map<String, Integer> requested = new LinkedHashMap<>();
        for (OrderItem line : items) {
            requested.merge(line.sku(), line.quantity(), Math::addExact);
        }
        return requested;
    }
}
