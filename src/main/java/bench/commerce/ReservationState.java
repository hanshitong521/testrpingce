package bench.commerce;

/**
 * Lifecycle of the stock held for one order.
 *
 * <p>A reservation may be consumed by exactly one terminal transition: released (unpaid
 * cancellation/expiry) or committed (payment). After that it must never move inventory again,
 * which is what makes repeated cancellations and duplicate callbacks harmless.
 */
enum ReservationState {
    RESERVED,
    RELEASED,
    COMMITTED
}
