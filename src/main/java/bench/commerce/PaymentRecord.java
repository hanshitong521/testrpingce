package bench.commerce;

final class PaymentRecord {
    final String paymentId;
    final String orderId;
    final long amount;
    PaymentStatus status;
    long refunded;

    PaymentRecord(String paymentId, String orderId, long amount, PaymentStatus status) {
        this.paymentId = paymentId;
        this.orderId = orderId;
        this.amount = amount;
        this.status = status;
    }
}
