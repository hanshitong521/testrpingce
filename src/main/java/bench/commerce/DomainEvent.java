package bench.commerce;

public record DomainEvent(long sequence, String type, String orderId, String reference, long amountCents) {
}
