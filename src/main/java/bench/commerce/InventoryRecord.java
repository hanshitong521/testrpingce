package bench.commerce;

final class InventoryRecord {
    int available;
    int reserved;
    int sold;

    InventoryRecord(int available) {
        this.available = available;
    }
}
