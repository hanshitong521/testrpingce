package bench.commerce;

import java.util.LinkedHashMap;
import java.util.Map;

final class Reservation {
    final Map<String, Integer> quantities = new LinkedHashMap<>();
    ReservationState state = ReservationState.RESERVED;
}
