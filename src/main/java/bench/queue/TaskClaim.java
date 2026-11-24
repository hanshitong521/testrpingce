package bench.queue;

/** A worker's lease capability. A claim becomes invalid after expiry or re-claim. */
public record TaskClaim(
        String taskId,
        String workerId,
        String leaseToken,
        int attempt,
        long leaseUntilMillis) {
}
