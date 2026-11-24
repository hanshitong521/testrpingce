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
        run("constructor rejects invalid limits", TaskQueueTest::constructorValidation);
        run("payload null rejected while blank keys disable idempotency",
                TaskQueueTest::payloadAndBlankKeys);
        run("enqueue snapshot and idempotency", TaskQueueTest::enqueueAndIdempotency);
        run("idempotency keys match exactly without trimming",
                TaskQueueTest::idempotencyKeyExactMatch);
        run("due-time ordering and delayed retry", TaskQueueTest::orderingAndRetryDelay);
        run("earliest due time wins and ties remain FIFO", TaskQueueTest::dueTimeOrderingAndFifo);
        run("lease expiry rejects stale worker and exhausts attempts", TaskQueueTest::leaseExpiry);
        run("expired lease rejects token before reclaim",
                TaskQueueTest::expiredLeaseRejectedBeforeReclaim);
        run("retry delay boundary and attempt exhaustion", TaskQueueTest::retryBoundaries);
        run("claim validation and overflow saturation",
                TaskQueueTest::claimValidationAndSaturation);
        run("acknowledged task is terminal", TaskQueueTest::acknowledgement);
        run("cancellation invalidates active lease", TaskQueueTest::cancellation);
        run("cancel ready, unknown and terminal tasks", TaskQueueTest::cancelReadyAndUnknown);
        run("snapshot list is immutable", TaskQueueTest::snapshotImmutable);
        run("concurrent claims do not duplicate tasks", TaskQueueTest::concurrentClaims);
        run("concurrent idempotent enqueue creates one task", TaskQueueTest::concurrentIdempotentEnqueue);
        System.out.println("PASS: " + passed + " tests");
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
        equal(0, first.attempts());
        equal(TaskStatus.READY, first.status());
        notEqual(withoutKeyA.id(), withoutKeyB.id());
        equal(3, queue.snapshotAll().size());

        TaskClaim claim = queue.claimNext("worker").orElseThrow();
        check(queue.acknowledge(claim), "task should acknowledge");
        TaskSnapshot terminalDuplicate = queue.enqueue("must not replace", "key-1");
        equal(TaskStatus.SUCCEEDED, terminalDuplicate.status());
        equal("alpha", terminalDuplicate.payload());
        equal(3, queue.snapshotAll().size());
    }

    private static void constructorValidation() {
        expectThrows(IllegalArgumentException.class, () -> new TaskQueue(new MutableTime(0L), 0L, 1));
        expectThrows(IllegalArgumentException.class, () -> new TaskQueue(new MutableTime(0L), -1L, 1));
        expectThrows(IllegalArgumentException.class, () -> new TaskQueue(new MutableTime(0L), 100L, 0));
        expectThrows(IllegalArgumentException.class, () -> new TaskQueue(new MutableTime(0L), 100L, -1));
        expectThrows(NullPointerException.class, () -> new TaskQueue(null, 100L, 1));
    }

    private static void payloadAndBlankKeys() {
        MutableTime time = new MutableTime(5L);
        TaskQueue queue = new TaskQueue(time, 100L, 2);
        expectThrows(NullPointerException.class, () -> queue.enqueue(null, "key"));

        TaskSnapshot emptyPayload = queue.enqueue("", " ");
        TaskSnapshot emptyPayloadAgain = queue.enqueue("", "  ");
        TaskSnapshot nullKey = queue.enqueue("null-key", null);
        TaskSnapshot emptyKeyA = queue.enqueue("empty-key-a", "");
        TaskSnapshot emptyKeyB = queue.enqueue("empty-key-b", "");

        equal("", emptyPayload.payload());
        equal(TaskStatus.READY, emptyPayload.status());
        equal(0, emptyPayload.attempts());
        equal(5L, emptyPayload.createdAtMillis());
        equal(5L, emptyPayload.availableAtMillis());
        notEqual(emptyPayload.id(), emptyPayloadAgain.id());
        notEqual(emptyPayload.id(), nullKey.id());
        notEqual(emptyKeyA.id(), emptyKeyB.id());
        equal(5, queue.snapshotAll().size());
    }

    private static void idempotencyKeyExactMatch() {
        MutableTime time = new MutableTime(0L);
        TaskQueue queue = new TaskQueue(time, 100L, 2);
        TaskSnapshot original = queue.enqueue("first", "KEY");
        TaskSnapshot spaced = queue.enqueue("different", " KEY ");
        TaskSnapshot duplicate = queue.enqueue("overwrite", "KEY");

        notEqual(original.id(), spaced.id());
        equal(original.id(), duplicate.id());
        equal("first", duplicate.payload());
        equal(TaskStatus.READY, duplicate.status());
        equal(0, duplicate.attempts());

        TaskClaim claim = queue.claimNext("worker").orElseThrow();
        check(queue.acknowledge(claim), "original claim should acknowledge");
        TaskSnapshot terminalDuplicate = queue.enqueue("ignored", "KEY");
        equal(original.id(), terminalDuplicate.id());
        equal(TaskStatus.SUCCEEDED, terminalDuplicate.status());
        equal(1, terminalDuplicate.attempts());
        equal("first", terminalDuplicate.payload());
    }

    private static void expiredLeaseRejectedBeforeReclaim() {
        MutableTime time = new MutableTime(0L);
        TaskQueue queue = new TaskQueue(time, 100L, 3);
        TaskSnapshot task = queue.enqueue("work", null);
        TaskClaim first = queue.claimNext("worker-1").orElseThrow();
        equal(100L, first.leaseUntilMillis());

        time.advance(100L);
        check(!queue.acknowledge(first), "expired claim must not acknowledge");
        check(!queue.retry(first, 0L), "expired claim must not retry");
        TaskSnapshot untouched = queue.snapshotAll().get(0);
        equal(TaskStatus.RUNNING, untouched.status());
        equal(1, untouched.attempts());
        equal(first.leaseUntilMillis(), untouched.leaseUntilMillis().longValue());

        TaskClaim second = queue.claimNext("worker-2").orElseThrow();
        equal(task.id(), second.taskId());
        equal(2, second.attempt());
        notEqual(first.leaseToken(), second.leaseToken());
        check(!queue.acknowledge(first), "stale token must not acknowledge after reclaim");
        check(!queue.retry(first, 0L), "stale token must not retry after reclaim");

        MutableTime boundary = new MutableTime(0L);
        TaskQueue beforeExpiry = new TaskQueue(boundary, 100L, 2);
        beforeExpiry.enqueue("work", null);
        TaskClaim active = beforeExpiry.claimNext("worker").orElseThrow();
        boundary.advance(99L);
        check(beforeExpiry.acknowledge(active), "lease is valid one millisecond before expiry");
    }

    private static void retryBoundaries() {
        MutableTime time = new MutableTime(0L);
        TaskQueue exhausted = new TaskQueue(time, 1_000L, 1);
        exhausted.enqueue("only", null);
        TaskClaim claim = exhausted.claimNext("worker").orElseThrow();
        expectThrows(IllegalArgumentException.class, () -> exhausted.retry(claim, -1L));
        check(exhausted.retry(claim, 0L), "zero delay must be accepted");
        TaskSnapshot dead = exhausted.snapshotAll().get(0);
        equal(TaskStatus.DEAD, dead.status());
        equal(null, dead.leaseUntilMillis());
        check(!exhausted.retry(claim, 0L), "dead task must reject further retries");
        check(!exhausted.acknowledge(claim), "dead task must reject acknowledge");

        TaskQueue delayed = new TaskQueue(time, 1_000L, 2);
        TaskSnapshot task = delayed.enqueue("delayed", null);
        TaskClaim first = delayed.claimNext("worker-a").orElseThrow();
        check(delayed.retry(first, 25L), "retry under limit must be accepted");
        TaskSnapshot ready = delayed.snapshotAll().get(0);
        equal(TaskStatus.READY, ready.status());
        equal(25L, ready.availableAtMillis());
        equal(null, ready.leaseUntilMillis());
        equal(1, ready.attempts());
        check(delayed.claimNext("worker-b").isEmpty(), "task must wait for its delay");

        time.advance(24L);
        check(delayed.claimNext("worker-c").isEmpty(), "task must still be delayed at 24ms");
        time.advance(1L);
        TaskClaim second = delayed.claimNext("worker-d").orElseThrow();
        equal(task.id(), second.taskId());
        equal(2, second.attempt());
        check(delayed.retry(second, 0L), "attempt at limit must still retry into DEAD");
        equal(TaskStatus.DEAD, delayed.snapshotAll().get(0).status());
    }

    private static void claimValidationAndSaturation() {
        TaskQueue queue = new TaskQueue(new MutableTime(0L), 100L, 2);
        expectThrows(IllegalArgumentException.class, () -> queue.claimNext(null));
        expectThrows(IllegalArgumentException.class, () -> queue.claimNext(""));
        expectThrows(IllegalArgumentException.class, () -> queue.claimNext("   "));
        check(queue.claimNext("worker").isEmpty(), "empty queue yields no claim");

        TaskQueue saturatingLease = new TaskQueue(new MutableTime(0L), Long.MAX_VALUE, 1);
        saturatingLease.enqueue("saturate-lease", null);
        TaskClaim leaseClaim = saturatingLease.claimNext("worker").orElseThrow();
        equal(Long.MAX_VALUE, leaseClaim.leaseUntilMillis());
        check(saturatingLease.acknowledge(leaseClaim), "saturated lease should stay valid");

        MutableTime time = new MutableTime(0L);
        TaskQueue saturatingDelay = new TaskQueue(time, 100L, 2);
        saturatingDelay.enqueue("saturate-delay", null);
        TaskClaim delayClaim = saturatingDelay.claimNext("worker").orElseThrow();
        check(saturatingDelay.retry(delayClaim, Long.MAX_VALUE), "huge delay should be accepted");
        equal(Long.MAX_VALUE, saturatingDelay.snapshotAll().get(0).availableAtMillis());
        check(saturatingDelay.claimNext("worker").isEmpty(), "saturated task is not yet due");
        time.advance(Long.MAX_VALUE);
        TaskClaim saturated = saturatingDelay.claimNext("worker").orElseThrow();
        equal(Long.MAX_VALUE, saturated.leaseUntilMillis());
    }

    private static void cancelReadyAndUnknown() {
        MutableTime time = new MutableTime(0L);
        TaskQueue queue = new TaskQueue(time, 100L, 2);
        TaskSnapshot ready = queue.enqueue("ready", null);
        check(queue.cancel(ready.id()), "ready task should be cancellable");
        TaskSnapshot cancelled = queue.snapshotAll().get(0);
        equal(TaskStatus.CANCELLED, cancelled.status());
        equal(null, cancelled.leaseUntilMillis());
        check(!queue.cancel(ready.id()), "cancelled task is terminal");
        check(!queue.cancel("missing-task"), "unknown task cannot be cancelled");
        check(queue.claimNext("worker").isEmpty(), "cancelled task must not be claimed");

        queue.enqueue("running", null);
        TaskClaim claim = queue.claimNext("worker").orElseThrow();
        TaskSnapshot running = queue.snapshotAll().get(1);
        equal(TaskStatus.RUNNING, running.status());
        equal(100L, running.leaseUntilMillis().longValue());
    }

    private static void snapshotImmutable() {
        TaskQueue queue = new TaskQueue(new MutableTime(0L), 100L, 2);
        queue.enqueue("payload", null);
        List<TaskSnapshot> snapshots = queue.snapshotAll();
        equal(1, snapshots.size());
        expectThrows(UnsupportedOperationException.class, snapshots::clear);
        expectThrows(UnsupportedOperationException.class,
                () -> snapshots.add(queue.enqueue("other", null)));
    }

    private static void orderingAndRetryDelay() {
        MutableTime time = new MutableTime(0L);
        TaskQueue queue = new TaskQueue(time, 500L, 3);
        TaskSnapshot a = queue.enqueue("A", null);
        TaskSnapshot b = queue.enqueue("B", null);
        TaskClaim claimA = queue.claimNext("worker-a").orElseThrow();
        check(queue.retry(claimA, 50L), "first retry should be accepted");

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
    }

    private static void acknowledgement() {
        TaskQueue queue = new TaskQueue(new MutableTime(0L), 100L, 2);
        TaskSnapshot task = queue.enqueue("once", null);
        TaskClaim claim = queue.claimNext("worker").orElseThrow();
        check(queue.acknowledge(claim), "valid claim should be acknowledged");
        check(!queue.acknowledge(claim), "claim cannot be acknowledged twice");
        check(queue.claimNext("another-worker").isEmpty(), "successful task is terminal");
        equal(TaskStatus.SUCCEEDED, queue.snapshotAll().get(0).status());
        equal(task.id(), queue.snapshotAll().get(0).id());
    }

    private static void cancellation() {
        TaskQueue queue = new TaskQueue(new MutableTime(0L), 100L, 2);
        TaskSnapshot task = queue.enqueue("cancel me", null);
        TaskClaim claim = queue.claimNext("worker").orElseThrow();
        check(queue.cancel(task.id()), "running task should be cancellable");
        check(!queue.acknowledge(claim), "cancel must invalidate the lease");
        check(!queue.cancel(task.id()), "terminal task cannot be cancelled again");
        equal(TaskStatus.CANCELLED, queue.snapshotAll().get(0).status());
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

    private static void concurrentIdempotentEnqueue() throws Exception {
        final int threadCount = 8;
        TaskQueue queue = new TaskQueue(new MutableTime(0L), 10_000L, 1);
        Set<String> ids = ConcurrentHashMap.newKeySet();
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);
        for (int i = 0; i < threadCount; i++) {
            Thread thread = new Thread(() -> {
                ready.countDown();
                try {
                    start.await();
                    ids.add(queue.enqueue("shared-payload", "shared-key").id());
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(exception);
                } finally {
                    done.countDown();
                }
            }, "enqueuer-" + i);
            thread.start();
        }
        ready.await();
        start.countDown();
        done.await();

        equal(1, ids.size());
        List<TaskSnapshot> snapshots = queue.snapshotAll();
        equal(1, snapshots.size());
        equal("shared-payload", snapshots.get(0).payload());
        equal(0, snapshots.get(0).attempts());
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

    private static void expectThrows(Class<? extends Throwable> expected, ThrowingRunnable action) {
        try {
            action.run();
        } catch (Throwable actual) {
            if (expected.isInstance(actual)) {
                return;
            }
            throw new AssertionError("expected " + expected.getSimpleName()
                    + " but got " + actual, actual);
        }
        throw new AssertionError("expected " + expected.getSimpleName() + " but nothing was thrown");
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
