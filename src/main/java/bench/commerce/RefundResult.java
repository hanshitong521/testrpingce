package bench.commerce;

public record RefundResult(boolean accepted, boolean duplicate, String reason, long totalRefundedCents) {
}
