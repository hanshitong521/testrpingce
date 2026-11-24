package bench.commerce;

public record OrderItem(String sku, int quantity, long unitPriceCents) {
    public OrderItem {
        if (sku == null || sku.isBlank()) {
            throw new IllegalArgumentException("sku is required");
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive");
        }
        if (unitPriceCents < 0) {
            throw new IllegalArgumentException("unit price cannot be negative");
        }
    }

    public long lineTotalCents() {
        return Math.multiplyExact((long) quantity, unitPriceCents);
    }
}
