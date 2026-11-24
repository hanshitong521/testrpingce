package bench.commerce;

final class RefundRecord {
    final String refundId;
    final String orderId;
    final long amount;

    RefundRecord(String refundId, String orderId, long amount) {
        this.refundId = refundId;
        this.orderId = orderId;
        this.amount = amount;
    }
}
