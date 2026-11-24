package bench.commerce;

/**
 * Inventory buckets for one SKU.
 *
 * <p>Every bucket move is expressed here so {@code available + reserved + sold} never drifts and no
 * bucket can go negative. A violated invariant is a programming error, not a business outcome, so it
 * fails loudly instead of silently corrupting stock.
 */
final class InventoryRecord {
    int available;
    int reserved;
    int sold;

    InventoryRecord(int available) {
        this.available = available;
    }

    void reserve(int quantity) {
        requireAtLeast(available, quantity, "available");
        available -= quantity;
        reserved += quantity;
    }

    void release(int quantity) {
        requireAtLeast(reserved, quantity, "reserved");
        reserved -= quantity;
        available += quantity;
    }

    void commit(int quantity) {
        requireAtLeast(reserved, quantity, "reserved");
        reserved -= quantity;
        sold += quantity;
    }

    private static void requireAtLeast(int bucket, int quantity, String bucketName) {
        if (bucket < quantity) {
            throw new IllegalStateException(
                    "cannot move " + quantity + " units out of " + bucket + " " + bucketName);
        }
    }
}
