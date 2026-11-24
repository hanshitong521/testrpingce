package bench.commerce;

/** Audit event names. Events are part of the observable contract, so the names live in one place. */
final class EventTypes {
    static final String ORDER_CREATED = "ORDER_CREATED";
    static final String ORDER_CANCELLED = "ORDER_CANCELLED";
    static final String PAYMENT_SUCCEEDED = "PAYMENT_SUCCEEDED";
    static final String PAYMENT_REFUND_REQUIRED = "PAYMENT_REFUND_REQUIRED";
    static final String REFUND_SUCCEEDED = "REFUND_SUCCEEDED";

    private EventTypes() {
    }
}
