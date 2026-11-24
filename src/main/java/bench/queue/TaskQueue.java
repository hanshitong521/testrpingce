package bench.queue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * In-memory task queue. All public operations are synchronized; this class is
 * intentionally dependency-free and scoped to a single process.
 */
public final class TaskQueue {
    private final TimeSource timeSource;
    private final long leaseDurationMillis;
    private final int maxAttempts;
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private final Map<String, String> idempotencyIndex = new LinkedHashMap<>();
    private long nextTaskId = 1;
    private long nextSequence = 1;
    private long nextLeaseToken = 1;

    public TaskQueue(TimeSource timeSource, long leaseDurationMillis, int maxAttempts) {
        this.timeSource = Objects.requireNonNull(timeSource, "timeSource");
        if (leaseDurationMillis <= 0) {
            throw new IllegalArgumentException("leaseDurationMillis must be positive");
        }
        if (maxAttempts <= 0) {
            throw new IllegalArgumentException("maxAttempts must be positive");
        }
        this.leaseDurationMillis = leaseDurationMillis;
        this.maxAttempts = maxAttempts;
    }

    /**
     * Enqueues a task. A nonblank idempotency key is unique for this queue's
     * lifetime; duplicate submissions return the original task unchanged.
     */
    public synchronized TaskSnapshot enqueue(String payload, String idempotencyKey) {
        Objects.requireNonNull(payload, "payload");
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            String existingId = idempotencyIndex.get(idempotencyKey);
            if (existingId != null) {
                return snapshot(entries.get(existingId));
            }
        }

        long now = timeSource.nowMillis();
        String id = "task-" + nextTaskId++;
        Entry entry = new Entry(id, payload, idempotencyKey, now, nextSequence++);
        entries.put(id, entry);
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            idempotencyIndex.put(idempotencyKey, id);
        }
        return snapshot(entry);
    }

    /** Claims the earliest eligible task, or returns empty when none is ready. */
    public synchronized Optional<TaskClaim> claimNext(String workerId) {
        if (workerId == null || workerId.isBlank()) {
            throw new IllegalArgumentException("workerId must not be blank");
        }
        long now = timeSource.nowMillis();
        expireLeases(now);

        Entry selected = null;
        for (Entry entry : entries.values()) {
            if (entry.status != TaskStatus.READY || entry.availableAtMillis > now) {
                continue;
            }
            if (selected == null
                    || entry.availableAtMillis < selected.availableAtMillis
                    || (entry.availableAtMillis == selected.availableAtMillis
                            && entry.sequence < selected.sequence)) {
                selected = entry;
            }
        }
        if (selected == null) {
            return Optional.empty();
        }

        selected.status = TaskStatus.RUNNING;
        selected.attempts++;
        selected.workerId = workerId;
        selected.leaseToken = "lease-" + nextLeaseToken++;
        selected.leaseUntilMillis = safeAdd(now, leaseDurationMillis);
        return Optional.of(new TaskClaim(selected.id, workerId, selected.leaseToken,
                selected.attempts, selected.leaseUntilMillis));
    }

    /** Marks a currently leased task as successful. Stale claims must be rejected. */
    public synchronized boolean acknowledge(TaskClaim claim) {
        Entry entry = findClaimedEntry(claim);
        if (entry == null) {
            return false;
        }
        entry.status = TaskStatus.SUCCEEDED;
        clearLease(entry);
        return true;
    }

    /**
     * Releases a currently leased task for retry after delayMillis. Once the
     * attempt limit is reached, the task becomes DEAD instead.
     */
    public synchronized boolean retry(TaskClaim claim, long delayMillis) {
        if (delayMillis < 0) {
            throw new IllegalArgumentException("delayMillis must not be negative");
        }
        Entry entry = findClaimedEntry(claim);
        if (entry == null) {
            return false;
        }
        if (entry.attempts >= maxAttempts) {
            entry.status = TaskStatus.DEAD;
        } else {
            entry.status = TaskStatus.READY;
            entry.availableAtMillis = safeAdd(timeSource.nowMillis(), delayMillis);
        }
        clearLease(entry);
        return true;
    }

    /** Cancels a non-terminal task, including one currently leased to a worker. */
    public synchronized boolean cancel(String taskId) {
        Entry entry = entries.get(taskId);
        if (entry == null || isTerminal(entry.status)) {
            return false;
        }
        entry.status = TaskStatus.CANCELLED;
        clearLease(entry);
        return true;
    }

    /** Returns an insertion-ordered immutable snapshot of all tasks. */
    public synchronized List<TaskSnapshot> snapshotAll() {
        List<TaskSnapshot> result = new ArrayList<>(entries.size());
        for (Entry entry : entries.values()) {
            result.add(snapshot(entry));
        }
        return List.copyOf(result);
    }

    private Entry findClaimedEntry(TaskClaim claim) {
        if (claim == null) {
            return null;
        }
        Entry entry = entries.get(claim.taskId());
        if (entry == null || entry.status != TaskStatus.RUNNING) {
            return null;
        }
        if (!Objects.equals(entry.workerId, claim.workerId())
                || !Objects.equals(entry.leaseToken, claim.leaseToken())) {
            return null;
        }
        if (entry.leaseUntilMillis == null || timeSource.nowMillis() >= entry.leaseUntilMillis) {
            return null;
        }
        return entry;
    }

    /** Reclaims expired leases: READY when attempts remain, DEAD once exhausted. */
    private void expireLeases(long now) {
        for (Entry entry : entries.values()) {
            if (entry.status == TaskStatus.RUNNING
                    && entry.leaseUntilMillis != null
                    && now >= entry.leaseUntilMillis) {
                if (entry.attempts >= maxAttempts) {
                    entry.status = TaskStatus.DEAD;
                } else {
                    entry.status = TaskStatus.READY;
                }
                clearLease(entry);
            }
        }
    }

    private static boolean isTerminal(TaskStatus status) {
        return status == TaskStatus.SUCCEEDED
                || status == TaskStatus.DEAD
                || status == TaskStatus.CANCELLED;
    }

    private static void clearLease(Entry entry) {
        entry.workerId = null;
        entry.leaseToken = null;
        entry.leaseUntilMillis = null;
    }

    private static TaskSnapshot snapshot(Entry entry) {
        return new TaskSnapshot(entry.id, entry.payload, entry.idempotencyKey,
                entry.status, entry.attempts, entry.createdAtMillis,
                entry.availableAtMillis, entry.leaseUntilMillis);
    }

    private static long safeAdd(long left, long right) {
        if (right > 0 && left > Long.MAX_VALUE - right) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }

    private static final class Entry {
        private final String id;
        private final String payload;
        private final String idempotencyKey;
        private final long createdAtMillis;
        private final long sequence;
        private TaskStatus status = TaskStatus.READY;
        private int attempts;
        private long availableAtMillis;
        private String workerId;
        private String leaseToken;
        private Long leaseUntilMillis;

        private Entry(String id, String payload, String idempotencyKey,
                      long createdAtMillis, long sequence) {
            this.id = id;
            this.payload = payload;
            this.idempotencyKey = idempotencyKey;
            this.createdAtMillis = createdAtMillis;
            this.availableAtMillis = createdAtMillis;
            this.sequence = sequence;
        }
    }
}
