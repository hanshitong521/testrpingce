package bench.commerce;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    boolean reserve(String orderId, List<OrderItem> items) {
        if (store.reservations.containsKey(orderId)) {
            return false;
        }
        Map<String, Integer> requested = new LinkedHashMap<>();
        for (OrderItem line : items) {
            requested.merge(line.sku(), line.quantity(), Math::addExact);
        }
        for (Map.Entry<String, Integer> line : requested.entrySet()) {
            InventoryRecord item = store.inventory.get(line.getKey());
            if (item == null || item.available < line.getValue()) {
                return false;
            }
        }
        Reservation reservation = new Reservation();
        reservation.quantities.putAll(requested);
        for (Map.Entry<String, Integer> line : requested.entrySet()) {
            InventoryRecord item = store.inventory.get(line.getKey());
            item.available -= line.getValue();
            item.reserved += line.getValue();
        }
        store.reservations.put(orderId, reservation);
        return true;
    }

    void release(String orderId) {
        Reservation reservation = store.reservations.get(orderId);
        if (reservation == null) {
            return;
        }
        // Historical code assumes a caller will only release a live reservation.
        for (Map.Entry<String, Integer> line : reservation.quantities.entrySet()) {
            InventoryRecord item = store.inventory.get(line.getKey());
            item.reserved -= line.getValue();
            item.available += line.getValue();
        }
        reservation.state = "RELEASED";
    }

    void commit(String orderId) {
        Reservation reservation = store.reservations.get(orderId);
        if (reservation == null) {
            return;
        }
        for (Map.Entry<String, Integer> line : reservation.quantities.entrySet()) {
            InventoryRecord item = store.inventory.get(line.getKey());
            item.reserved -= line.getValue();
            item.sold += line.getValue();
        }
        reservation.state = "COMMITTED";
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
}
