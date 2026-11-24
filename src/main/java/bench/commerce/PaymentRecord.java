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

    boolean sameRequest(String orderId, long amount) {
        return this.orderId.equals(orderId) && this.amount == amount;
    }

    boolean capturesOrder() {
        return status == PaymentStatus.CAPTURED
                || status == PaymentStatus.PARTIALLY_REFUNDED
                || status == PaymentStatus.REFUNDED;
    }

    boolean refundable() {
        return status == PaymentStatus.CAPTURED || status == PaymentStatus.PARTIALLY_REFUNDED;
    }

    void applyRefund(long amountCents) {
        refunded += amountCents;
        status = refunded >= amount ? PaymentStatus.REFUNDED : PaymentStatus.PARTIALLY_REFUNDED;
    }
}
