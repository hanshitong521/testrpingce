package bench.queue;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/** Small dependency-free visible test suite. Run with run-tests.ps1 or run-tests.sh. */
public final class TaskQueueTest {
    private static int passed;

    private TaskQueueTest() {
    }

    public static void main(String[] args) throws Exception {
        run("constructor and argument validation", TaskQueueTest::argumentValidation);
        run("payload and idempotency key semantics", TaskQueueTest::payloadAndKeySemantics);
        run("enqueue snapshot and idempotency", TaskQueueTest::enqueueAndIdempotency);
        run("snapshot list is immutable and ordered", TaskQueueTest::snapshotImmutability);
        run("due-time ordering and delayed retry", TaskQueueTest::orderingAndRetryDelay);
        run("earliest due time wins and ties remain FIFO", TaskQueueTest::dueTimeOrderingAndFifo);
        run("lease expiry rejects stale worker and exhausts attempts", TaskQueueTest::leaseExpiry);
        run("expired lease is reclaimed to READY", TaskQueueTest::expiredLeaseReclaimedToReady);
        run("expired claim rejects acknowledge and retry", TaskQueueTest::expiredClaimRejected);
        run("max attempts boundary turns task DEAD", TaskQueueTest::maxAttemptsBoundary);
        run("lease and retry deadlines saturate on overflow", TaskQueueTest::deadlineOverflow);
        run("acknowledged task is terminal", TaskQueueTest::acknowledgement);
        run("cancellation invalidates active lease", TaskQueueTest::cancellation);
        run("ready task can be cancelled", TaskQueueTest::cancellationOfReadyTask);
        run("concurrent claims do not duplicate tasks", TaskQueueTest::concurrentClaims);
        run("concurrent claims stay exclusive without acknowledgement",
                TaskQueueTest::concurrentClaimsWithoutAcknowledgement);
        System.out.println("PASS: " + passed + " tests");
    }

    private static void argumentValidation() {
        MutableTime time = new MutableTime(0L);
        expectThrows(NullPointerException.class, "null time source must be rejected",
                () -> new TaskQueue(null, 100L, 1));
        expectThrows(IllegalArgumentException.class, "zero lease must be rejected",
                () -> new TaskQueue(time, 0L, 1));
        expectThrows(IllegalArgumentException.class, "negative lease must be rejected",
                () -> new TaskQueue(time, -1L, 1));
        expectThrows(IllegalArgumentException.class, "zero maxAttempts must be rejected",
                () -> new TaskQueue(time, 100L, 0));
        expectThrows(IllegalArgumentException.class, "negative maxAttempts must be rejected",
                () -> new TaskQueue(time, 100L, -1));

        TaskQueue queue = new TaskQueue(time, 100L, 2);
        expectThrows(IllegalArgumentException.class, "null worker must be rejected",
                () -> queue.claimNext(null));
        expectThrows(IllegalArgumentException.class, "blank worker must be rejected",
                () -> queue.claimNext("   "));
        expectThrows(IllegalArgumentException.class, "empty worker must be rejected",
                () -> queue.claimNext(""));

        expectThrows(NullPointerException.class, "null payload must be rejected",
                () -> queue.enqueue(null, "key"));
        equal("", queue.enqueue("", "key").payload());

        queue.enqueue("work", null);
        TaskClaim claim = queue.claimNext("worker").orElseThrow();
        expectThrows(IllegalArgumentException.class, "negative retry delay must be rejected",
                () -> queue.retry(claim, -1L));
        equal(TaskStatus.RUNNING, queue.snapshotAll().get(0).status());
        check(queue.acknowledge(claim), "rejected retry must not damage the lease");
        equal(claim.taskId(), queue.snapshotAll().get(0).id());
    }

    private static void payloadAndKeySemantics() {
        MutableTime time = new MutableTime(0L);
        TaskQueue queue = new TaskQueue(time, 100L, 2);
        TaskSnapshot keyWithSpaces = queue.enqueue("spaces", " key ");
        TaskSnapshot trimmedKey = queue.enqueue("trimmed", "key");
        notEqual(keyWithSpaces.id(), trimmedKey.id());
        equal("spaces", queue.enqueue("overwrite", " key ").payload());
        equal("trimmed", trimmedKey.payload());

        TaskSnapshot blankA = queue.enqueue("blank-a", "");
        TaskSnapshot blankB = queue.enqueue("blank-b", "   ");
        TaskSnapshot blankC = queue.enqueue("blank-c", null);
        equal(5, queue.snapshotAll().size());
        notEqual(blankA.id(), blankB.id());
        notEqual(blankB.id(), blankC.id());
    }

    private static void enqueueAndIdempotency() {
        MutableTime time = new MutableTime(1_000L);
        TaskQueue queue = new TaskQueue(time, 100L, 3);
        TaskSnapshot first = queue.enqueue("alpha", "key-1");
        time.advance(20L);
        TaskSnapshot duplicate = queue.enqueue("different payload", "key-1");
        TaskSnapshot withoutKeyA = queue.enqueue("no-key-a", " ");
        TaskSnapshot withoutKeyB = queue.enqueue("no-key-b", null);

        equal("task-1", first.id());
        equal(first, duplicate);
        equal(1_000L, first.createdAtMillis());
        equal(1_000L, first.availableAtMillis());
        equal(0, first.attempts());
        equal(TaskStatus.READY, first.status());
        notEqual(withoutKeyA.id(), withoutKeyB.id());
        equal(3, queue.snapshotAll().size());

        TaskClaim claim = queue.claimNext("worker").orElseThrow();
        check(queue.acknowledge(claim), "task should acknowledge");
        TaskSnapshot terminalDuplicate = queue.enqueue("must not replace", "key-1");
        equal(TaskStatus.SUCCEEDED, terminalDuplicate.status());
        equal("alpha", terminalDuplicate.payload());
        equal(1, terminalDuplicate.attempts());
        equal(1_000L, terminalDuplicate.createdAtMillis());
        equal(3, queue.snapshotAll().size());
    }

    private static void snapshotImmutability() {
        TaskQueue queue = new TaskQueue(new MutableTime(0L), 100L, 2);
        queue.enqueue("first", null);
        queue.enqueue("second", null);
        TaskSnapshot third = queue.enqueue("third", null);
        List<TaskSnapshot> snapshots = queue.snapshotAll();
        equal("first", snapshots.get(0).payload());
        equal("second", snapshots.get(1).payload());
        equal("third", snapshots.get(2).payload());
        expectThrows(UnsupportedOperationException.class, "snapshot list must be immutable",
                () -> snapshots.add(third));
        expectThrows(UnsupportedOperationException.class, "snapshot list must not be clearable",
                snapshots::clear);
        equal(3, queue.snapshotAll().size());
    }

    private static void orderingAndRetryDelay() {
        MutableTime time = new MutableTime(0L);
        TaskQueue queue = new TaskQueue(time, 500L, 3);
        TaskSnapshot a = queue.enqueue("A", null);
        TaskSnapshot b = queue.enqueue("B", null);
        TaskClaim claimA = queue.claimNext("worker-a").orElseThrow();
        check(queue.retry(claimA, 50L), "first retry should be accepted");
        equal(50L, queue.snapshotAll().get(0).availableAtMillis());
        equal(TaskStatus.READY, queue.snapshotAll().get(0).status());
        check(queue.snapshotAll().get(0).leaseUntilMillis() == null, "retry must clear the lease");

        TaskClaim claimB = queue.claimNext("worker-b").orElseThrow();
        equal(b.id(), claimB.taskId());
        check(queue.acknowledge(claimB), "B should be acknowledged");
        check(queue.claimNext("worker-c").isEmpty(), "A must remain delayed");

        time.advance(50L);
        TaskClaim secondA = queue.claimNext("worker-d").orElseThrow();
        equal(a.id(), secondA.taskId());
        equal(2, secondA.attempt());
    }

    private static void dueTimeOrderingAndFifo() {
        MutableTime time = new MutableTime(0L);
        TaskQueue queue = new TaskQueue(time, 1_000L, 3);
        TaskSnapshot a = queue.enqueue("A", null);
        TaskSnapshot b = queue.enqueue("B", null);
        TaskClaim claimA = queue.claimNext("worker-a").orElseThrow();
        check(queue.retry(claimA, 20L), "A retry should be accepted");
        TaskClaim claimB = queue.claimNext("worker-b").orElseThrow();
        check(queue.retry(claimB, 10L), "B retry should be accepted");
        time.advance(20L);

        TaskClaim first = queue.claimNext("worker-c").orElseThrow();
        equal(b.id(), first.taskId());
        TaskClaim second = queue.claimNext("worker-d").orElseThrow();
        equal(a.id(), second.taskId());

        TaskQueue tieQueue = new TaskQueue(time, 1_000L, 3);
        TaskSnapshot firstEnqueued = tieQueue.enqueue("first", null);
        TaskSnapshot secondEnqueued = tieQueue.enqueue("second", null);
        TaskClaim firstLease = tieQueue.claimNext("worker-e").orElseThrow();
        check(tieQueue.retry(firstLease, 10L), "first retry should be accepted");
        TaskClaim secondLease = tieQueue.claimNext("worker-f").orElseThrow();
        check(tieQueue.retry(secondLease, 10L), "second retry should be accepted");
        time.advance(10L);
        equal(firstEnqueued.id(), tieQueue.claimNext("worker-g").orElseThrow().taskId());
        equal(secondEnqueued.id(), tieQueue.claimNext("worker-h").orElseThrow().taskId());
    }

    private static void leaseExpiry() {
        MutableTime time = new MutableTime(10L);
        TaskQueue queue = new TaskQueue(time, 100L, 2);
        TaskSnapshot task = queue.enqueue("work", null);
        TaskClaim first = queue.claimNext("worker-1").orElseThrow();
        equal(110L, first.leaseUntilMillis());
        time.advance(100L);
        check(!queue.acknowledge(first), "expired lease must not be acknowledged");

        TaskClaim second = queue.claimNext("worker-2").orElseThrow();
        equal(task.id(), second.taskId());
        equal(2, second.attempt());
        check(!queue.acknowledge(first), "old token must remain stale after re-claim");
        time.advance(100L);
        check(queue.claimNext("worker-3").isEmpty(), "exhausted task must not be re-claimed");
        TaskSnapshot finalState = queue.snapshotAll().get(0);
        equal(TaskStatus.DEAD, finalState.status());
        equal(2, finalState.attempts());
        check(finalState.leaseUntilMillis() == null, "DEAD task must not keep a lease");
    }

    private static void expiredLeaseReclaimedToReady() {
        MutableTime time = new MutableTime(0L);
        TaskQueue queue = new TaskQueue(time, 50L, 3);
        TaskSnapshot a = queue.enqueue("A", null);
        TaskSnapshot b = queue.enqueue("B", null);

        TaskClaim claimA = queue.claimNext("worker-1").orElseThrow();
        equal(a.id(), claimA.taskId());
        equal(1, claimA.attempt());
        TaskClaim claimB = queue.claimNext("worker-2").orElseThrow();
        equal(b.id(), claimB.taskId());
        equal(1, claimB.attempt());

        // Both leases expire at t=50. The next claim reclaims both to READY and
        // then leases A again, so B's reclaimed READY state stays observable.
        time.advance(50L);
        TaskClaim reclaimA = queue.claimNext("worker-3").orElseThrow();
        equal(a.id(), reclaimA.taskId());
        equal(2, reclaimA.attempt());
        TaskSnapshot reclaimedB = queue.snapshotAll().get(1);
        equal(TaskStatus.READY, reclaimedB.status());
        equal(1, reclaimedB.attempts());
        check(reclaimedB.leaseUntilMillis() == null, "reclaimed task must have no lease");
        check(!queue.acknowledge(claimB), "expired claim must stay rejected after reclaim");
        check(!queue.retry(claimB, 0L), "expired claim must not retry after reclaim");

        TaskClaim reclaimB = queue.claimNext("worker-4").orElseThrow();
        equal(b.id(), reclaimB.taskId());
        equal(2, reclaimB.attempt());
        equal(0L, queue.snapshotAll().get(1).availableAtMillis());
    }

    private static void expiredClaimRejected() {
        MutableTime time = new MutableTime(10L);
        TaskQueue queue = new TaskQueue(time, 100L, 3);
        TaskSnapshot task = queue.enqueue("work", null);
        TaskClaim claim = queue.claimNext("worker-1").orElseThrow();

        time.advance(100L);
        check(!queue.acknowledge(claim), "expired lease must not be acknowledged");
        check(!queue.retry(claim, 0L), "expired lease must not be retried");
        TaskSnapshot stillRunning = queue.snapshotAll().get(0);
        equal(TaskStatus.RUNNING, stillRunning.status());
        equal(1, stillRunning.attempts());

        TaskClaim reclaimed = queue.claimNext("worker-2").orElseThrow();
        equal(task.id(), reclaimed.taskId());
        equal(2, reclaimed.attempt());
        notEqual(claim.leaseToken(), reclaimed.leaseToken());
        check(!queue.acknowledge(claim), "old token must stay stale after re-claim");
        check(!queue.retry(claim, 0L), "old token must stay stale after re-claim");

        TaskClaim forged = new TaskClaim(task.id(), "worker-2",
                claim.leaseToken(), claim.attempt(), claim.leaseUntilMillis());
        check(!queue.acknowledge(forged), "forged token must be rejected");
        check(!queue.retry(forged, 0L), "forged token must not retry");
        check(queue.acknowledge(reclaimed), "current claim should be accepted");
    }

    private static void maxAttemptsBoundary() {
        MutableTime time = new MutableTime(0L);
        TaskQueue queue = new TaskQueue(time, 100L, 2);
        TaskSnapshot task = queue.enqueue("work", null);

        TaskClaim first = queue.claimNext("worker-1").orElseThrow();
        equal(1, first.attempt());
        check(queue.retry(first, 0L), "attempt 1 should be retryable");
        equal(TaskStatus.READY, queue.snapshotAll().get(0).status());

        TaskClaim second = queue.claimNext("worker-2").orElseThrow();
        equal(2, second.attempt());
        check(queue.retry(second, 0L), "the last allowed attempt must accept retry");
        TaskSnapshot dead = queue.snapshotAll().get(0);
        equal(task.id(), dead.id());
        equal(TaskStatus.DEAD, dead.status());
        equal(2, dead.attempts());
        check(dead.leaseUntilMillis() == null, "DEAD task must not keep a lease");
        check(queue.claimNext("worker-3").isEmpty(), "DEAD task must not be claimed");
        check(!queue.retry(second, 0L), "stale claim must not revive a DEAD task");
        check(!queue.cancel(task.id()), "DEAD task is terminal");
        check(!queue.acknowledge(second), "DEAD task must reject its claim");
    }

    private static void deadlineOverflow() {
        long nearMax = Long.MAX_VALUE - 10L;
        MutableTime time = new MutableTime(nearMax);
        TaskQueue queue = new TaskQueue(time, 1_000L, 3);
        queue.enqueue("overflow", null);
        TaskClaim claim = queue.claimNext("worker-1").orElseThrow();
        equal(Long.MAX_VALUE, claim.leaseUntilMillis());
        equal(Long.MAX_VALUE, queue.snapshotAll().get(0).leaseUntilMillis().longValue());
        check(queue.acknowledge(claim), "saturated lease is still valid before the deadline");

        TaskSnapshot retried = queue.enqueue("retry-overflow", null);
        TaskClaim retriedClaim = queue.claimNext("worker-2").orElseThrow();
        equal(retried.id(), retriedClaim.taskId());
        check(queue.retry(retriedClaim, Long.MAX_VALUE), "huge delay should be accepted");
        TaskSnapshot delayed = queue.snapshotAll().get(1);
        equal(TaskStatus.READY, delayed.status());
        equal(Long.MAX_VALUE, delayed.availableAtMillis());
        check(delayed.availableAtMillis() >= time.nowMillis(), "deadline must not wrap negative");
        check(!queue.cancel("missing-task"), "unknown task cannot be cancelled");
    }

    private static void acknowledgement() {
        TaskQueue queue = new TaskQueue(new MutableTime(0L), 100L, 2);
        TaskSnapshot task = queue.enqueue("once", null);
        TaskClaim claim = queue.claimNext("worker").orElseThrow();
        check(queue.acknowledge(claim), "valid claim should be acknowledged");
        check(!queue.acknowledge(claim), "claim cannot be acknowledged twice");
        check(queue.claimNext("another-worker").isEmpty(), "successful task is terminal");
        TaskSnapshot finalState = queue.snapshotAll().get(0);
        equal(TaskStatus.SUCCEEDED, finalState.status());
        equal(task.id(), finalState.id());
        equal(1, finalState.attempts());
        check(finalState.leaseUntilMillis() == null, "SUCCEEDED task must not keep a lease");
        check(!queue.retry(claim, 0L), "SUCCEEDED task must reject retry");
        check(!queue.cancel(task.id()), "SUCCEEDED task is terminal");
    }

    private static void cancellation() {
        TaskQueue queue = new TaskQueue(new MutableTime(0L), 100L, 2);
        TaskSnapshot task = queue.enqueue("cancel me", null);
        TaskClaim claim = queue.claimNext("worker").orElseThrow();
        equal(TaskStatus.RUNNING, queue.snapshotAll().get(0).status());
        check(queue.cancel(task.id()), "running task should be cancellable");
        check(!queue.acknowledge(claim), "cancel must invalidate the lease");
        check(!queue.retry(claim, 0L), "cancel must invalidate retry too");
        check(!queue.cancel(task.id()), "terminal task cannot be cancelled again");
        check(queue.claimNext("another").isEmpty(), "cancelled task must not be claimed");
        TaskSnapshot finalState = queue.snapshotAll().get(0);
        equal(TaskStatus.CANCELLED, finalState.status());
        check(finalState.leaseUntilMillis() == null, "cancelled task must not keep a lease");
    }

    private static void cancellationOfReadyTask() {
        TaskQueue queue = new TaskQueue(new MutableTime(0L), 100L, 2);
        TaskSnapshot ready = queue.enqueue("ready", "cancel-key");
        queue.enqueue("waiting", null);
        check(queue.cancel(ready.id()), "ready task should be cancellable");
        TaskSnapshot duplicate = queue.enqueue("ignored", "cancel-key");
        equal(TaskStatus.CANCELLED, duplicate.status());
        equal("ready", duplicate.payload());
        equal(0, duplicate.attempts());
        check(queue.claimNext("worker").isPresent(), "other ready task should remain claimable");
        check(queue.claimNext("worker-2").isEmpty(), "cancelled task must not be claimable");
    }

    private static void concurrentClaims() throws Exception {
        final int taskCount = 120;
        final int workerCount = 8;
        TaskQueue queue = new TaskQueue(new MutableTime(0L), 10_000L, 1);
        for (int i = 0; i < taskCount; i++) {
            queue.enqueue("payload-" + i, null);
        }

        Set<String> claimedIds = ConcurrentHashMap.newKeySet();
        AtomicInteger successfulClaims = new AtomicInteger();
        CountDownLatch ready = new CountDownLatch(workerCount);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(workerCount);
        for (int i = 0; i < workerCount; i++) {
            final String worker = "worker-" + i;
            Thread thread = new Thread(() -> {
                ready.countDown();
                try {
                    start.await();
                    while (true) {
                        Optional<TaskClaim> next = queue.claimNext(worker);
                        if (next.isEmpty()) {
                            break;
                        }
                        TaskClaim claim = next.orElseThrow();
                        if (!claimedIds.add(claim.taskId())) {
                            throw new AssertionError("duplicate active claim: " + claim.taskId());
                        }
                        successfulClaims.incrementAndGet();
                        if (!queue.acknowledge(claim)) {
                            throw new AssertionError("fresh claim could not be acknowledged");
                        }
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(exception);
                } finally {
                    done.countDown();
                }
            }, worker);
            thread.start();
        }
        ready.await();
        start.countDown();
        done.await();

        equal(taskCount, successfulClaims.get());
        equal(taskCount, claimedIds.size());
        List<TaskSnapshot> snapshots = queue.snapshotAll();
        equal(taskCount, snapshots.size());
        for (TaskSnapshot snapshot : snapshots) {
            equal(TaskStatus.SUCCEEDED, snapshot.status());
            equal(1, snapshot.attempts());
        }
        equal(taskCount, new HashSet<>(claimedIds).size());
    }

    private static void concurrentClaimsWithoutAcknowledgement() throws Exception {
        final int taskCount = 200;
        final int workerCount = 8;
        TaskQueue queue = new TaskQueue(new MutableTime(0L), 10_000L, 1);
        for (int i = 0; i < taskCount; i++) {
            queue.enqueue("payload-" + i, null);
        }

        Set<String> activeClaims = ConcurrentHashMap.newKeySet();
        AtomicInteger claimCount = new AtomicInteger();
        CountDownLatch ready = new CountDownLatch(workerCount);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(workerCount);
        for (int i = 0; i < workerCount; i++) {
            final String worker = "worker-" + i;
            Thread thread = new Thread(() -> {
                ready.countDown();
                try {
                    start.await();
                    while (true) {
                        Optional<TaskClaim> next = queue.claimNext(worker);
                        if (next.isEmpty()) {
                            break;
                        }
                        if (!activeClaims.add(next.orElseThrow().taskId())) {
                            throw new AssertionError("task claimed by two workers");
                        }
                        claimCount.incrementAndGet();
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(exception);
                } finally {
                    done.countDown();
                }
            }, worker);
            thread.start();
        }
        ready.await();
        start.countDown();
        done.await();

        equal(taskCount, claimCount.get());
        equal(taskCount, activeClaims.size());
        for (TaskSnapshot snapshot : queue.snapshotAll()) {
            equal(TaskStatus.RUNNING, snapshot.status());
            equal(1, snapshot.attempts());
        }
    }

    private static void run(String name, ThrowingRunnable test) throws Exception {
        test.run();
        passed++;
        System.out.println("PASS: " + name);
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void equal(Object expected, Object actual) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError("expected <" + expected + "> but was <" + actual + ">");
        }
    }

    private static void equal(long expected, long actual) {
        if (expected != actual) {
            throw new AssertionError("expected <" + expected + "> but was <" + actual + ">");
        }
    }

    private static void equal(int expected, int actual) {
        if (expected != actual) {
            throw new AssertionError("expected <" + expected + "> but was <" + actual + ">");
        }
    }

    private static void notEqual(Object left, Object right) {
        if (left == null ? right == null : left.equals(right)) {
            throw new AssertionError("values should differ but both were <" + left + ">");
        }
    }

    private static void expectThrows(Class<? extends Throwable> expectedType,
                                     String message, ThrowingRunnable action) {
        try {
            action.run();
        } catch (Throwable thrown) {
            if (expectedType.isInstance(thrown)) {
                return;
            }
            throw new AssertionError(message + " (threw <" + thrown + ">)", thrown);
        }
        throw new AssertionError(message + " (no exception was thrown)");
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private static final class MutableTime implements TimeSource {
        private long now;

        private MutableTime(long now) {
            this.now = now;
        }

        @Override
        public long nowMillis() {
            return now;
        }

        private void advance(long millis) {
            now += millis;
        }
    }
}
