package bench.commerce;

import java.util.LinkedHashMap;
import java.util.Map;

final class Reservation {
    final Map<String, Integer> quantities;
    ReservationState state = ReservationState.RESERVED;

    Reservation(Map<String, Integer> quantities) {
        this.quantities = new LinkedHashMap<>(quantities);
    }

    boolean isReserved() {
        return state == ReservationState.RESERVED;
    }
}
