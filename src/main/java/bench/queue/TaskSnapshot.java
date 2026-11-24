package bench.queue;

/** Immutable public view of a queued task. leaseUntilMillis is null unless RUNNING. */
public record TaskSnapshot(
        String id,
        String payload,
        String idempotencyKey,
        TaskStatus status,
        int attempts,
        long createdAtMillis,
        long availableAtMillis,
        Long leaseUntilMillis) {
}
