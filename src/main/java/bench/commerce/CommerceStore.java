package bench.commerce;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

final class CommerceStore {
    final Object mutex = new Object();
    final Map<String, Order> orders = new LinkedHashMap<>();
    final Map<String, InventoryRecord> inventory = new LinkedHashMap<>();
    final Map<String, Reservation> reservations = new LinkedHashMap<>();
    final Map<String, Coupon> coupons = new LinkedHashMap<>();
    final Map<String, PaymentRecord> payments = new LinkedHashMap<>();
    final Map<String, RefundRecord> refunds = new LinkedHashMap<>();
    final List<DomainEvent> events = new ArrayList<>();
    long eventSequence;

    /**
     * Runs one business operation as a single critical section. Every public entry point goes through
     * here, so a state check and the side effects it triggers cannot be split by another thread.
     * The lock is reentrant, so services may call each other inside one transaction.
     */
    <T> T transaction(Supplier<T> operation) {
        synchronized (mutex) {
            return operation.get();
        }
    }

    void transaction(Runnable operation) {
        synchronized (mutex) {
            operation.run();
        }
    }

    void event(String type, String orderId, String reference, long amount) {
        events.add(new DomainEvent(++eventSequence, type, orderId, reference, amount));
    }

    /**
     * The sale payment that refunds apply to. Late or duplicate captures are recorded as
     * {@link PaymentStatus#REFUND_REQUIRED} and are deliberately not part of the order's refundable
     * captured amount.
     */
    PaymentRecord capturedPaymentFor(String orderId) {
        for (PaymentRecord payment : payments.values()) {
            if (payment.orderId.equals(orderId) && payment.status != PaymentStatus.REFUND_REQUIRED) {
                return payment;
            }
        }
        return null;
    }
}
