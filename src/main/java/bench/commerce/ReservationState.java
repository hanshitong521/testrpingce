package bench.commerce;

/** Terminal-aware lifecycle of an inventory reservation. */
enum ReservationState {
    RESERVED,
    COMMITTED,
    RELEASED
}
