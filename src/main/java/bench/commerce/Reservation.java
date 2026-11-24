package bench.commerce;

import java.util.LinkedHashMap;
import java.util.Map;

/** One order's stock hold. Transitions are one-way to keep release/commit idempotent. */
final class Reservation {
    enum State { RESERVED, RELEASED, COMMITTED }

    final Map<String, Integer> quantities = new LinkedHashMap<>();
    State state = State.RESERVED;
}
