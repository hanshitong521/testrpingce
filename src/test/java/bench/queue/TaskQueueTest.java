package bench.queue;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Small dependency-free visible test suite. Run with run-tests.ps1 or run-tests.sh. */
public final class TaskQueueTest {
    private static int passed;

    private TaskQueueTest() {
    }

    public static void main(String[] args) throws Exception {
        run("constructor validation", TaskQueueTest::constructorValidation);
        run("enqueue validation and idempotency key semantics",
                TaskQueueTest::enqueueValidationAndKeySemantics);
        run("enqueue snapshot and idempotency", TaskQueueTest::enqueueAndIdempotency);
        run("idempotency across task states", TaskQueueTest::idempotencyAcrossTaskStates);
        run("claim validation and lease fields", TaskQueueTest::claimValidationAndLeaseFields);
        run("due-time ordering and delayed retry", TaskQueueTest::orderingAndRetryDelay);
        run("earliest due time wins and ties remain FIFO", TaskQueueTest::dueTimeOrderingAndFifo);
        run("expired leases reclaim in FIFO order", TaskQueueTest::expiredLeaseReclamationOrdering);
        run("lease expiry rejects stale worker and exhausts attempts", TaskQueueTest::leaseExpiry);
        run("retry delay and attempt limit", TaskQueueTest::retryDelayAndAttemptLimit);
        run("overflow saturates lease and retry deadlines", TaskQueueTest::overflowSaturatesDeadlines);
        run("acknowledged task is terminal", TaskQueueTest::acknowledgement);
        run("cancellation invalidates active lease", TaskQueueTest::cancellation);
        run("concurrent claims do not duplicate tasks", TaskQueueTest::concurrentClaims);
        run("concurrent enqueue is idempotent", TaskQueueTest::concurrentIdempotentEnqueue);
        run("snapshots are immutable and insertion ordered",
                TaskQueueTest::snapshotImmutabilityAndOrder);
        System.out.println("PASS: " + passed + " tests");
    }

    private static void constructorValidation() {
        MutableTime time = new MutableTime(0L);
        expectThrows(IllegalArgumentException.class, () -> new TaskQueue(time, 0L, 1));
        expectThrows(IllegalArgumentException.class, () -> new TaskQueue(time, -1L, 1));
        expectThrows(IllegalArgumentException.class, () -> new TaskQueue(time, 1L, 0));
        expectThrows(IllegalArgumentException.class, () -> new TaskQueue(time, 1L, -1));
        check(new TaskQueue(time, 1L, 1).snapshotAll().isEmpty(),
                "positive lease and attempt limits must be accepted");
    }

    private static void enqueueValidationAndKeySemantics() {
        MutableTime time = new MutableTime(1_000L);
        TaskQueue queue = new TaskQueue(time, 100L, 2);

        expectThrows(NullPointerException.class, () -> queue.enqueue(null, "null-payload"));
        check(queue.snapshotAll().isEmpty(), "null payload must not create a task");

        TaskSnapshot emptyKey = queue.enqueue("empty-key", "");
        TaskSnapshot nullKey = queue.enqueue("null-key", null);
        TaskSnapshot blankKey = queue.enqueue("blank-key", " \t\n");
        TaskSnapshot emptyPayload = queue.enqueue("", "empty-payload");
        TaskSnapshot exactKey = queue.enqueue("first", "key-1");
        TaskSnapshot spacedKey = queue.enqueue("second", " key-1 ");

        notEqual(emptyKey.id(), nullKey.id());
        notEqual(emptyKey.id(), blankKey.id());
        notEqual(nullKey.id(), blankKey.id());
        notEqual(exactKey.id(), spacedKey.id());
        equal("", emptyPayload.payload());
        equal(6, queue.snapshotAll().size());

        TaskSnapshot exactDuplicate = queue.enqueue("replacement", "key-1");
        TaskSnapshot spacedDuplicate = queue.enqueue("replacement", " key-1 ");
        equal(exactKey, exactDuplicate);
        equal(spacedKey, spacedDuplicate);
        equal(6, queue.snapshotAll().size());

        for (TaskSnapshot snapshot : queue.snapshotAll()) {
            equal(1_000L, snapshot.createdAtMillis());
            equal(1_000L, snapshot.availableAtMillis());
            equal(TaskStatus.READY, snapshot.status());
            equal(0, snapshot.attempts());
        }
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

    private static void idempotencyAcrossTaskStates() {
        MutableTime time = new MutableTime(0L);
        TaskQueue queue = new TaskQueue(time, 100L, 1);

        TaskSnapshot runningTask = queue.enqueue("original", "running-key");
        TaskClaim runningClaim = queue.claimNext("worker-1").orElseThrow();
        TaskSnapshot runningSnapshot = snapshotById(queue, runningTask.id());
        TaskSnapshot runningDuplicate = queue.enqueue("changed", "running-key");
        equal(runningSnapshot, runningDuplicate);
        equal(TaskStatus.RUNNING, runningDuplicate.status());
        equal(1, runningDuplicate.attempts());
        check(queue.acknowledge(runningClaim), "running task should acknowledge");
        TaskSnapshot succeededSnapshot = snapshotById(queue, runningTask.id());
        equal(succeededSnapshot, queue.enqueue("changed", "running-key"));

        TaskSnapshot deadTask = queue.enqueue("dead", "dead-key");
        TaskClaim deadClaim = queue.claimNext("worker-2").orElseThrow();
        check(queue.retry(deadClaim, 0L), "final attempt retry should transition to DEAD");
        TaskSnapshot deadSnapshot = snapshotById(queue, deadTask.id());
        equal(TaskStatus.DEAD, deadSnapshot.status());
        equal(deadSnapshot, queue.enqueue("changed", "dead-key"));

        TaskSnapshot cancelledTask = queue.enqueue("cancel", "cancel-key");
        check(queue.cancel(cancelledTask.id()), "ready task should cancel");
        TaskSnapshot cancelledSnapshot = snapshotById(queue, cancelledTask.id());
        equal(cancelledSnapshot, queue.enqueue("changed", "cancel-key"));
        equal(3, queue.snapshotAll().size());
    }

    private static void claimValidationAndLeaseFields() {
        MutableTime time = new MutableTime(500L);
        TaskQueue queue = new TaskQueue(time, 100L, 3);
        TaskSnapshot task = queue.enqueue("work", null);

        expectThrows(IllegalArgumentException.class, () -> queue.claimNext(null));
        expectThrows(IllegalArgumentException.class, () -> queue.claimNext(""));
        expectThrows(IllegalArgumentException.class, () -> queue.claimNext(" \t\n"));
        TaskSnapshot unchanged = snapshotById(queue, task.id());
        equal(TaskStatus.READY, unchanged.status());
        equal(0, unchanged.attempts());
        equal(null, unchanged.leaseUntilMillis());

        TaskClaim claim = queue.claimNext("worker-1").orElseThrow();
        equal(task.id(), claim.taskId());
        equal("worker-1", claim.workerId());
        check(claim.leaseToken() != null && !claim.leaseToken().isBlank(),
                "claim must include a lease token");
        equal(1, claim.attempt());
        equal(600L, claim.leaseUntilMillis());

        TaskSnapshot running = snapshotById(queue, task.id());
        equal(TaskStatus.RUNNING, running.status());
        equal(1, running.attempts());
        equal(Long.valueOf(600L), running.leaseUntilMillis());
        check(queue.claimNext("worker-2").isEmpty(), "the only task is already running");
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
        TaskQueue fifoQueue = new TaskQueue(time, 1_000L, 3);
        TaskSnapshot fifoFirst = fifoQueue.enqueue("first", null);
        TaskSnapshot fifoSecond = fifoQueue.enqueue("second", null);
        TaskSnapshot fifoThird = fifoQueue.enqueue("third", null);
        equal(fifoFirst.id(), fifoQueue.claimNext("worker-1").orElseThrow().taskId());
        equal(fifoSecond.id(), fifoQueue.claimNext("worker-2").orElseThrow().taskId());
        equal(fifoThird.id(), fifoQueue.claimNext("worker-3").orElseThrow().taskId());

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

    private static void expiredLeaseReclamationOrdering() {
        MutableTime time = new MutableTime(0L);
        TaskQueue queue = new TaskQueue(time, 10L, 2);
        TaskSnapshot a = queue.enqueue("A", null);
        TaskSnapshot b = queue.enqueue("B", null);
        TaskClaim firstA = queue.claimNext("worker-a").orElseThrow();
        TaskClaim firstB = queue.claimNext("worker-b").orElseThrow();
        equal(a.id(), firstA.taskId());
        equal(b.id(), firstB.taskId());
        equal(10L, firstA.leaseUntilMillis());
        equal(10L, firstB.leaseUntilMillis());

        time.advance(10L);
        check(!queue.acknowledge(firstA), "expired lease must not acknowledge before reclamation");
        check(!queue.retry(firstA, 0L), "expired lease must not retry before reclamation");
        check(!queue.acknowledge(firstB), "expired lease must not acknowledge before reclamation");
        check(!queue.retry(firstB, 0L), "expired lease must not retry before reclamation");
        equal(TaskStatus.RUNNING, snapshotById(queue, a.id()).status());
        equal(TaskStatus.RUNNING, snapshotById(queue, b.id()).status());

        TaskClaim reclaimedA = queue.claimNext("worker-a").orElseThrow();
        equal(a.id(), reclaimedA.taskId());
        equal(2, reclaimedA.attempt());
        equal(20L, reclaimedA.leaseUntilMillis());
        notEqual(firstA.leaseToken(), reclaimedA.leaseToken());

        TaskSnapshot readyB = snapshotById(queue, b.id());
        equal(TaskStatus.READY, readyB.status());
        equal(1, readyB.attempts());
        equal(null, readyB.leaseUntilMillis());

        check(!queue.acknowledge(firstA), "old token must fail after re-claim");
        check(!queue.retry(firstA, 0L), "old token must fail after re-claim");
        check(!queue.acknowledge(firstB), "old token must fail after re-claim");
        check(!queue.retry(firstB, 0L), "old token must fail after re-claim");

        TaskClaim reclaimedB = queue.claimNext("worker-b").orElseThrow();
        equal(b.id(), reclaimedB.taskId());
        equal(2, reclaimedB.attempt());
        equal(20L, reclaimedB.leaseUntilMillis());
        check(queue.acknowledge(reclaimedA), "re-claimed task should acknowledge");

        time.advance(10L);
        check(queue.claimNext("worker-e").isEmpty(), "exhausted task must not be re-claimed");
        TaskSnapshot deadB = snapshotById(queue, b.id());
        equal(TaskStatus.DEAD, deadB.status());
        equal(2, deadB.attempts());
        equal(null, deadB.leaseUntilMillis());
        equal(TaskStatus.SUCCEEDED, snapshotById(queue, a.id()).status());
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

    private static void retryDelayAndAttemptLimit() {
        MutableTime time = new MutableTime(0L);
        TaskQueue queue = new TaskQueue(time, 100L, 2);
        TaskSnapshot task = queue.enqueue("work", null);
        TaskClaim first = queue.claimNext("worker-a").orElseThrow();
        equal(100L, first.leaseUntilMillis());

        time.advance(30L);
        check(queue.retry(first, 10L), "first retry should be accepted");
        TaskSnapshot delayed = snapshotById(queue, task.id());
        equal(TaskStatus.READY, delayed.status());
        equal(1, delayed.attempts());
        equal(40L, delayed.availableAtMillis());
        equal(null, delayed.leaseUntilMillis());
        check(!queue.acknowledge(first), "old token must fail after a valid retry");

        time.advance(9L);
        check(queue.claimNext("worker-b").isEmpty(), "retry delay must be honored exactly");
        time.advance(1L);
        TaskClaim second = queue.claimNext("worker-c").orElseThrow();
        equal(2, second.attempt());
        equal(140L, second.leaseUntilMillis());

        TaskSnapshot running = snapshotById(queue, task.id());
        expectThrows(IllegalArgumentException.class, () -> queue.retry(second, -1L));
        equal(running, snapshotById(queue, task.id()));

        check(queue.retry(second, 0L), "retry on the final attempt should succeed");
        TaskSnapshot dead = snapshotById(queue, task.id());
        equal(TaskStatus.DEAD, dead.status());
        equal(2, dead.attempts());
        equal(null, dead.leaseUntilMillis());
        check(!queue.retry(second, 0L), "dead task must reject the old claim");
        check(!queue.acknowledge(second), "dead task must reject acknowledgement");
        check(!queue.cancel(task.id()), "dead task cannot be cancelled");
        check(queue.claimNext("worker-d").isEmpty(), "dead task is terminal");
    }

    private static void overflowSaturatesDeadlines() {
        long start = Long.MAX_VALUE - 50L;
        MutableTime time = new MutableTime(start);
        TaskQueue queue = new TaskQueue(time, 100L, 2);
        TaskSnapshot task = queue.enqueue("work", null);
        equal(start, task.availableAtMillis());

        TaskClaim claim = queue.claimNext("worker").orElseThrow();
        equal(Long.MAX_VALUE, claim.leaseUntilMillis());
        equal(Long.valueOf(Long.MAX_VALUE), snapshotById(queue, task.id()).leaseUntilMillis());

        check(queue.retry(claim, 100L), "retry should accept a delay that saturates");
        TaskSnapshot delayed = snapshotById(queue, task.id());
        equal(TaskStatus.READY, delayed.status());
        equal(Long.MAX_VALUE, delayed.availableAtMillis());
        equal(null, delayed.leaseUntilMillis());
        check(queue.claimNext("worker-2").isEmpty(),
                "saturated future availability must not be claimable early");
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
        MutableTime time = new MutableTime(0L);
        TaskQueue queue = new TaskQueue(time, 100L, 2);
        TaskSnapshot ready = queue.enqueue("ready", null);
        check(queue.cancel(ready.id()), "ready task should be cancellable");
        TaskSnapshot cancelledReady = snapshotById(queue, ready.id());
        equal(TaskStatus.CANCELLED, cancelledReady.status());
        equal(0, cancelledReady.attempts());
        equal(null, cancelledReady.leaseUntilMillis());
        check(!queue.cancel(ready.id()), "cancelled task cannot be cancelled again");
        check(!queue.cancel("missing-task"), "missing task cannot be cancelled");
        check(!queue.cancel(null), "null task id cannot be cancelled");

        TaskSnapshot task = queue.enqueue("cancel me", null);
        TaskClaim claim = queue.claimNext("worker").orElseThrow();
        check(queue.cancel(task.id()), "running task should be cancellable");
        check(!queue.acknowledge(claim), "cancel must invalidate the lease");
        check(!queue.retry(claim, 0L), "cancel must invalidate retry");
        check(!queue.cancel(task.id()), "terminal task cannot be cancelled again");
        TaskSnapshot cancelledRunning = snapshotById(queue, task.id());
        equal(TaskStatus.CANCELLED, cancelledRunning.status());
        equal(1, cancelledRunning.attempts());
        equal(null, cancelledRunning.leaseUntilMillis());
        check(queue.claimNext("worker-2").isEmpty(), "cancelled task cannot be re-claimed");

        TaskSnapshot succeeded = queue.enqueue("succeed", null);
        TaskClaim succeededClaim = queue.claimNext("worker-3").orElseThrow();
        check(queue.acknowledge(succeededClaim), "task should acknowledge");
        check(!queue.cancel(succeeded.id()), "succeeded task cannot be cancelled");
        equal(TaskStatus.SUCCEEDED, snapshotById(queue, succeeded.id()).status());
    }

    private static void snapshotImmutabilityAndOrder() {
        MutableTime time = new MutableTime(0L);
        TaskQueue queue = new TaskQueue(time, 100L, 2);
        TaskSnapshot a = queue.enqueue("A", null);
        TaskSnapshot b = queue.enqueue("B", null);
        TaskSnapshot c = queue.enqueue("C", null);

        List<TaskSnapshot> before = queue.snapshotAll();
        equal(List.of(a.id(), b.id(), c.id()),
                before.stream().map(TaskSnapshot::id).toList());
        expectThrows(UnsupportedOperationException.class, () -> before.add(before.get(0)));
        TaskSnapshot originalA = before.get(0);

        TaskClaim claimA = queue.claimNext("worker").orElseThrow();
        check(queue.retry(claimA, 10L), "A retry should be accepted");
        List<TaskSnapshot> after = queue.snapshotAll();
        equal(List.of(a.id(), b.id(), c.id()),
                after.stream().map(TaskSnapshot::id).toList());
        TaskSnapshot retriedA = after.get(0);
        equal(TaskStatus.READY, retriedA.status());
        equal(1, retriedA.attempts());
        equal(10L, retriedA.availableAtMillis());
        equal(null, retriedA.leaseUntilMillis());

        equal(TaskStatus.READY, originalA.status());
        equal(0, originalA.attempts());
        equal(0L, originalA.availableAtMillis());
        equal(null, originalA.leaseUntilMillis());
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
        AtomicReference<Throwable> failure = new AtomicReference<>();
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
                    failure.compareAndSet(null, exception);
                } catch (Throwable error) {
                    failure.compareAndSet(null, error);
                } finally {
                    done.countDown();
                }
            }, worker);
            thread.start();
        }
        ready.await();
        start.countDown();
        done.await();

        throwIfFailed(failure);
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
        final int workerCount = 8;
        final int submissionsPerWorker = 50;
        TaskQueue queue = new TaskQueue(new MutableTime(0L), 10_000L, 1);
        Set<String> taskIds = ConcurrentHashMap.newKeySet();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch ready = new CountDownLatch(workerCount);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(workerCount);
        for (int i = 0; i < workerCount; i++) {
            Thread thread = new Thread(() -> {
                ready.countDown();
                try {
                    start.await();
                    for (int submission = 0; submission < submissionsPerWorker; submission++) {
                        taskIds.add(queue.enqueue("shared payload", "shared-key").id());
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    failure.compareAndSet(null, exception);
                } catch (Throwable error) {
                    failure.compareAndSet(null, error);
                } finally {
                    done.countDown();
                }
            }, "enqueue-worker-" + i);
            thread.start();
        }
        ready.await();
        start.countDown();
        done.await();

        throwIfFailed(failure);
        equal(1, taskIds.size());
        equal(1, queue.snapshotAll().size());
        TaskSnapshot task = queue.snapshotAll().get(0);
        equal("shared payload", task.payload());
        equal("shared-key", task.idempotencyKey());
        equal(TaskStatus.READY, task.status());
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

    private static TaskSnapshot snapshotById(TaskQueue queue, String taskId) {
        return queue.snapshotAll().stream()
                .filter(snapshot -> snapshot.id().equals(taskId))
                .findFirst()
                .orElseThrow();
    }

    private static <T extends Throwable> T expectThrows(
            Class<T> expectedType, ThrowingRunnable action) {
        try {
            action.run();
        } catch (Throwable actual) {
            if (expectedType.isInstance(actual)) {
                return expectedType.cast(actual);
            }
            throw new AssertionError(
                    "expected " + expectedType.getSimpleName() + " but got " + actual, actual);
        }
        throw new AssertionError("expected " + expectedType.getSimpleName() + " but nothing was thrown");
    }

    private static void throwIfFailed(AtomicReference<Throwable> failure) {
        Throwable error = failure.get();
        if (error != null) {
            throw new AssertionError("worker failed", error);
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
