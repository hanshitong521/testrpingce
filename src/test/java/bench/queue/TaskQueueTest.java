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
        run("enqueue snapshot and idempotency", TaskQueueTest::enqueueAndIdempotency);
        run("argument validation", TaskQueueTest::validation);
        run("idempotency edge cases and terminal duplicates", TaskQueueTest::idempotencyEdgeCases);
        run("due-time ordering and delayed retry", TaskQueueTest::orderingAndRetryDelay);
        run("earliest due time wins and ties remain FIFO", TaskQueueTest::dueTimeOrderingAndFifo);
        run("availability ordering across retries", TaskQueueTest::availabilityOrdering);
        run("lease expiry rejects stale worker and exhausts attempts", TaskQueueTest::leaseExpiry);
        run("lease boundary and expiry before recovery", TaskQueueTest::leaseBoundaryAndRecovery);
        run("lease and retry deadlines saturate on overflow", TaskQueueTest::deadlineOverflow);
        run("attempt limit boundary", TaskQueueTest::attemptLimitBoundary);
        run("retry delay uses retry clock", TaskQueueTest::retryDelay);
        run("acknowledged task is terminal", TaskQueueTest::acknowledgement);
        run("cancellation invalidates active lease", TaskQueueTest::cancellation);
        run("cancellation semantics", TaskQueueTest::cancellationSemantics);
        run("snapshots are immutable and insertion ordered", TaskQueueTest::snapshots);
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

    private static void validation() {
        MutableTime time = new MutableTime(0L);
        expectThrows(IllegalArgumentException.class, () -> new TaskQueue(time, 0L, 1));
        expectThrows(IllegalArgumentException.class, () -> new TaskQueue(time, -1L, 1));
        expectThrows(IllegalArgumentException.class, () -> new TaskQueue(time, 1L, 0));
        expectThrows(IllegalArgumentException.class, () -> new TaskQueue(time, 1L, -1));
        expectThrows(NullPointerException.class, () -> new TaskQueue(null, 1L, 1));

        TaskQueue queue = new TaskQueue(time, 100L, 2);
        expectThrows(NullPointerException.class, () -> queue.enqueue(null, null));
        equal(0, queue.snapshotAll().size());
        queue.enqueue("payload", null);
        expectThrows(IllegalArgumentException.class, () -> queue.claimNext(null));
        expectThrows(IllegalArgumentException.class, () -> queue.claimNext(""));
        expectThrows(IllegalArgumentException.class, () -> queue.claimNext("   "));
        equal(TaskStatus.READY, queue.snapshotAll().get(0).status());

        TaskClaim claim = queue.claimNext("worker").orElseThrow();
        TaskSnapshot before = queue.snapshotAll().get(0);
        expectThrows(IllegalArgumentException.class, () -> queue.retry(claim, -1L));
        equal(before, queue.snapshotAll().get(0));
    }

    private static void idempotencyEdgeCases() {
        MutableTime time = new MutableTime(1_000L);
        TaskQueue queue = new TaskQueue(time, 100L, 2);
        TaskSnapshot emptyPayload = queue.enqueue("", "empty-payload");
        equal("", emptyPayload.payload());
        equal(TaskStatus.READY, emptyPayload.status());
        equal(0, emptyPayload.attempts());
        equal(1_000L, emptyPayload.createdAtMillis());
        equal(1_000L, emptyPayload.availableAtMillis());

        TaskClaim lease = queue.claimNext("worker").orElseThrow();
        equal(emptyPayload.id(), lease.taskId());
        TaskSnapshot duplicateRunning = queue.enqueue("replacement", "empty-payload");
        equal(emptyPayload.id(), duplicateRunning.id());
        equal(TaskStatus.RUNNING, duplicateRunning.status());
        equal(1, duplicateRunning.attempts());
        equal("", duplicateRunning.payload());
        equal(1_000L, duplicateRunning.createdAtMillis());
        equal(1, queue.snapshotAll().size());

        time.advance(10L);
        check(queue.retry(lease, 20L), "retry should be accepted");
        TaskSnapshot duplicateReady = queue.enqueue("replacement", "empty-payload");
        equal(TaskStatus.READY, duplicateReady.status());
        equal(1, duplicateReady.attempts());
        equal(1_030L, duplicateReady.availableAtMillis());
        equal("", duplicateReady.payload());
        equal(1, queue.snapshotAll().size());

        check(queue.cancel(emptyPayload.id()), "ready task should be cancellable");
        TaskSnapshot duplicateCancelled = queue.enqueue("replacement", "empty-payload");
        equal(emptyPayload.id(), duplicateCancelled.id());
        equal(TaskStatus.CANCELLED, duplicateCancelled.status());
        equal(1, duplicateCancelled.attempts());
        equal("", duplicateCancelled.payload());
        equal(1, queue.snapshotAll().size());

        TaskSnapshot nullKey = queue.enqueue("a", null);
        TaskSnapshot emptyKey = queue.enqueue("b", "");
        TaskSnapshot blankKey = queue.enqueue("c", "   ");
        TaskSnapshot tabKey = queue.enqueue("d", "\t");
        notEqual(nullKey.id(), emptyKey.id());
        notEqual(emptyKey.id(), blankKey.id());
        notEqual(blankKey.id(), tabKey.id());
        equal(5, queue.snapshotAll().size());

        TaskQueue exactMatch = new TaskQueue(time, 100L, 3);
        TaskSnapshot padded = exactMatch.enqueue("padded", " key ");
        TaskSnapshot plain = exactMatch.enqueue("plain", "key");
        notEqual(padded.id(), plain.id());
        equal(padded, exactMatch.enqueue("other", " key "));
        equal(plain, exactMatch.enqueue("other", "key"));
        equal(2, exactMatch.snapshotAll().size());

        TaskQueue deadQueue = new TaskQueue(time, 100L, 1);
        TaskSnapshot dead = deadQueue.enqueue("dead", "dead-key");
        TaskClaim deadClaim = deadQueue.claimNext("worker").orElseThrow();
        check(deadQueue.retry(deadClaim, 0L), "retry at attempt limit should dead-letter");
        TaskSnapshot duplicateDead = deadQueue.enqueue("other", "dead-key");
        equal(dead.id(), duplicateDead.id());
        equal(TaskStatus.DEAD, duplicateDead.status());
        equal(1, duplicateDead.attempts());
        equal("dead", duplicateDead.payload());
        equal(1, deadQueue.snapshotAll().size());
    }

    private static void availabilityOrdering() {
        MutableTime time = new MutableTime(0L);
        TaskQueue queue = new TaskQueue(time, 1_000L, 3);
        TaskSnapshot a = queue.enqueue("A", null);
        TaskSnapshot b = queue.enqueue("B", null);
        TaskSnapshot c = queue.enqueue("C", null);

        TaskClaim claimA = queue.claimNext("worker-a").orElseThrow();
        equal(a.id(), claimA.taskId());
        check(queue.retry(claimA, 30L), "A retry should be accepted");
        TaskClaim claimB = queue.claimNext("worker-b").orElseThrow();
        equal(b.id(), claimB.taskId());
        check(queue.retry(claimB, 30L), "B retry should be accepted");
        TaskClaim claimC = queue.claimNext("worker-c").orElseThrow();
        equal(c.id(), claimC.taskId());
        check(queue.acknowledge(claimC), "C should be acknowledged");

        time.set(20L);
        TaskSnapshot d = queue.enqueue("D", null);
        TaskSnapshot e = queue.enqueue("E", null);
        equal(d.id(), queue.claimNext("worker-d").orElseThrow().taskId());
        equal(e.id(), queue.claimNext("worker-e").orElseThrow().taskId());
        check(queue.claimNext("worker-f").isEmpty(), "retried tasks are not due yet");

        time.set(30L);
        TaskClaim secondA = queue.claimNext("worker-g").orElseThrow();
        equal(a.id(), secondA.taskId());
        equal(2, secondA.attempt());
        TaskClaim secondB = queue.claimNext("worker-h").orElseThrow();
        equal(b.id(), secondB.taskId());
        equal(2, secondB.attempt());
    }

    private static void leaseBoundaryAndRecovery() {
        MutableTime time = new MutableTime(0L);
        TaskQueue queue = new TaskQueue(time, 100L, 2);
        TaskSnapshot task = queue.enqueue("work", null);
        TaskClaim first = queue.claimNext("worker-1").orElseThrow();
        equal(100L, first.leaseUntilMillis());

        time.set(99L);
        check(queue.acknowledge(first), "lease should be valid just before expiry");

        MutableTime expiryTime = new MutableTime(0L);
        TaskQueue expiring = new TaskQueue(expiryTime, 100L, 2);
        TaskSnapshot expiringTask = expiring.enqueue("expiring", null);
        TaskClaim expired = expiring.claimNext("worker-2").orElseThrow();
        expiryTime.set(100L);

        TaskSnapshot beforeRecovery = expiring.snapshotAll().get(0);
        equal(TaskStatus.RUNNING, beforeRecovery.status());
        check(!expiring.acknowledge(expired), "claim at lease boundary must be expired");
        check(!expiring.retry(expired, 10L), "expired claim must not retry");
        equal(beforeRecovery, expiring.snapshotAll().get(0));

        TaskClaim recovered = expiring.claimNext("worker-3").orElseThrow();
        equal(expiringTask.id(), recovered.taskId());
        equal(2, recovered.attempt());
        equal(200L, recovered.leaseUntilMillis());
        check(!expiring.acknowledge(expired), "old token must fail after re-claim");
        check(!expiring.retry(expired, 0L), "old token must not retry after re-claim");

        TaskSnapshot active = expiring.snapshotAll().get(0);
        equal(TaskStatus.RUNNING, active.status());
        equal(2, active.attempts());
        equal(Long.valueOf(200L), active.leaseUntilMillis());
        check(expiring.acknowledge(recovered), "current claim should be acknowledged");
        equal(TaskStatus.SUCCEEDED, expiring.snapshotAll().get(0).status());
        equal(null, expiring.snapshotAll().get(0).leaseUntilMillis());
    }

    private static void deadlineOverflow() {
        MutableTime time = new MutableTime(Long.MAX_VALUE - 10L);
        TaskQueue queue = new TaskQueue(time, 100L, 2);
        TaskSnapshot task = queue.enqueue("overflow", null);
        TaskClaim claim = queue.claimNext("worker").orElseThrow();
        equal(Long.MAX_VALUE, claim.leaseUntilMillis());

        check(queue.retry(claim, 100L), "valid claim should retry");
        TaskSnapshot retried = queue.snapshotAll().get(0);
        equal(TaskStatus.READY, retried.status());
        equal(Long.MAX_VALUE, retried.availableAtMillis());
        equal(null, retried.leaseUntilMillis());
        check(queue.claimNext("worker").isEmpty(), "saturated deadline must stay in the future");

        time.set(Long.MAX_VALUE);
        TaskClaim second = queue.claimNext("worker-2").orElseThrow();
        equal(task.id(), second.taskId());
        equal(2, second.attempt());
        equal(Long.MAX_VALUE, second.leaseUntilMillis());
    }

    private static void attemptLimitBoundary() {
        MutableTime time = new MutableTime(0L);
        TaskQueue singleAttempt = new TaskQueue(time, 100L, 1);
        TaskSnapshot task = singleAttempt.enqueue("one", null);
        TaskClaim claim = singleAttempt.claimNext("worker").orElseThrow();
        equal(1, claim.attempt());
        check(singleAttempt.retry(claim, 0L), "retry at max attempts should succeed");

        TaskSnapshot dead = singleAttempt.snapshotAll().get(0);
        equal(task.id(), dead.id());
        equal(TaskStatus.DEAD, dead.status());
        equal(1, dead.attempts());
        equal(null, dead.leaseUntilMillis());
        check(singleAttempt.claimNext("worker").isEmpty(), "dead task must not be claimable");
        check(!singleAttempt.acknowledge(claim), "dead task must reject old claim");
        check(!singleAttempt.retry(claim, 0L), "dead task must reject retry");
        check(!singleAttempt.cancel(task.id()), "dead task must not be cancellable");

        TaskQueue twoAttempts = new TaskQueue(time, 100L, 2);
        TaskSnapshot two = twoAttempts.enqueue("two", null);
        TaskClaim first = twoAttempts.claimNext("worker").orElseThrow();
        check(twoAttempts.retry(first, 0L), "first retry should be accepted");
        TaskClaim second = twoAttempts.claimNext("worker").orElseThrow();
        equal(2, second.attempt());
        check(twoAttempts.retry(second, 0L), "second retry should dead-letter");
        TaskSnapshot terminal = twoAttempts.snapshotAll().get(0);
        equal(two.id(), terminal.id());
        equal(TaskStatus.DEAD, terminal.status());
        equal(2, terminal.attempts());
    }

    private static void retryDelay() {
        MutableTime time = new MutableTime(1_000L);
        TaskQueue queue = new TaskQueue(time, 100L, 3);
        TaskSnapshot task = queue.enqueue("delayed", null);
        TaskClaim first = queue.claimNext("worker").orElseThrow();
        equal(1_100L, first.leaseUntilMillis());

        time.advance(10L);
        check(queue.retry(first, 25L), "retry should be accepted");
        TaskSnapshot retried = queue.snapshotAll().get(0);
        equal(task.id(), retried.id());
        equal(TaskStatus.READY, retried.status());
        equal(1, retried.attempts());
        equal(1_035L, retried.availableAtMillis());
        equal(null, retried.leaseUntilMillis());

        time.set(1_034L);
        check(queue.claimNext("worker").isEmpty(), "task must not be claimable early");
        time.set(1_035L);
        TaskClaim second = queue.claimNext("worker").orElseThrow();
        equal(2, second.attempt());
        equal(1_135L, second.leaseUntilMillis());
        check(queue.acknowledge(second), "retried task should complete");

        TaskQueue immediate = new TaskQueue(time, 100L, 2);
        immediate.enqueue("immediate", null);
        TaskClaim immediateClaim = immediate.claimNext("worker").orElseThrow();
        check(immediate.retry(immediateClaim, 0L), "zero delay retry should be accepted");
        equal(1_035L, immediate.snapshotAll().get(0).availableAtMillis());
        equal(2, immediate.claimNext("worker").orElseThrow().attempt());
    }

    private static void cancellationSemantics() {
        MutableTime time = new MutableTime(0L);
        TaskQueue queue = new TaskQueue(time, 100L, 2);
        TaskSnapshot ready = queue.enqueue("ready", null);
        check(queue.cancel(ready.id()), "ready task should be cancellable");
        TaskSnapshot cancelled = queue.snapshotAll().get(0);
        equal(TaskStatus.CANCELLED, cancelled.status());
        equal(null, cancelled.leaseUntilMillis());
        check(queue.claimNext("worker").isEmpty(), "cancelled task must not be claimable");
        check(!queue.cancel(ready.id()), "cancelled task is terminal");
        check(!queue.cancel("missing-task"), "unknown task must not be cancellable");
        check(!queue.cancel(null), "null task id must not be cancellable");

        TaskSnapshot running = queue.enqueue("running", null);
        TaskClaim claim = queue.claimNext("worker").orElseThrow();
        equal(running.id(), claim.taskId());
        check(queue.cancel(running.id()), "running task should be cancellable");
        TaskSnapshot cancelledRunning = queue.snapshotAll().get(1);
        equal(TaskStatus.CANCELLED, cancelledRunning.status());
        equal(null, cancelledRunning.leaseUntilMillis());
        check(!queue.acknowledge(claim), "cancel must reject the old claim");
        check(!queue.retry(claim, 0L), "cancel must reject retry");
        check(!queue.cancel(running.id()), "cancelled running task is terminal");

        TaskSnapshot succeeded = queue.enqueue("succeeded", null);
        TaskClaim succeededClaim = queue.claimNext("worker").orElseThrow();
        check(queue.acknowledge(succeededClaim), "task should succeed");
        check(!queue.cancel(succeeded.id()), "succeeded task is terminal");

        TaskQueue singleAttempt = new TaskQueue(time, 100L, 1);
        singleAttempt.enqueue("dead", null);
        TaskClaim deadClaim = singleAttempt.claimNext("worker").orElseThrow();
        check(singleAttempt.retry(deadClaim, 0L), "retry should dead-letter");
        check(!singleAttempt.cancel(singleAttempt.snapshotAll().get(0).id()),
                "dead task is terminal");
    }

    private static void snapshots() {
        MutableTime time = new MutableTime(0L);
        TaskQueue queue = new TaskQueue(time, 100L, 2);
        TaskSnapshot a = queue.enqueue("A", null);
        time.advance(5L);
        TaskSnapshot b = queue.enqueue("B", null);
        time.advance(5L);
        TaskSnapshot c = queue.enqueue("C", null);

        List<TaskSnapshot> snapshots = queue.snapshotAll();
        equal(3, snapshots.size());
        equal(a.id(), snapshots.get(0).id());
        equal(b.id(), snapshots.get(1).id());
        equal(c.id(), snapshots.get(2).id());
        equal(0L, snapshots.get(0).createdAtMillis());
        equal(5L, snapshots.get(1).createdAtMillis());
        equal(10L, snapshots.get(2).createdAtMillis());

        TaskSnapshot held = snapshots.get(0);
        TaskClaim claim = queue.claimNext("worker").orElseThrow();
        equal(a.id(), claim.taskId());
        check(queue.acknowledge(claim), "task should succeed");
        equal(TaskStatus.READY, held.status());
        equal(0, held.attempts());
        equal(null, held.leaseUntilMillis());

        TaskSnapshot updated = queue.snapshotAll().get(0);
        equal(TaskStatus.SUCCEEDED, updated.status());
        equal(1, updated.attempts());
        unequalListOrder(queue.snapshotAll(), a, b, c);
        expectThrows(UnsupportedOperationException.class, () -> snapshots.add(a));
        expectThrows(UnsupportedOperationException.class, () -> snapshots.set(0, c));
        expectThrows(UnsupportedOperationException.class, () -> snapshots.remove(0));
    }

    private static void concurrentIdempotentEnqueue() throws Exception {
        final int threadCount = 16;
        TaskQueue queue = new TaskQueue(new MutableTime(0L), 10_000L, 2);
        Set<String> ids = ConcurrentHashMap.newKeySet();
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);
        for (int i = 0; i < threadCount; i++) {
            final int index = i;
            Thread thread = new Thread(() -> {
                ready.countDown();
                try {
                    start.await();
                    TaskSnapshot snapshot = queue.enqueue("payload-" + index, "shared-key");
                    ids.add(snapshot.id());
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(exception);
                } finally {
                    done.countDown();
                }
            }, "enqueue-" + i);
            thread.start();
        }
        ready.await();
        start.countDown();
        done.await();

        equal(1, ids.size());
        equal(1, queue.snapshotAll().size());
        TaskSnapshot only = queue.snapshotAll().get(0);
        equal(TaskStatus.READY, only.status());
        equal(0, only.attempts());
        equal(ids.iterator().next(), only.id());
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

    private static void unequalListOrder(List<TaskSnapshot> snapshots,
                                         TaskSnapshot first,
                                         TaskSnapshot second,
                                         TaskSnapshot third) {
        equal(first.id(), snapshots.get(0).id());
        equal(second.id(), snapshots.get(1).id());
        equal(third.id(), snapshots.get(2).id());
    }

    private static void expectThrows(Class<? extends Throwable> expectedType,
                                     ThrowingRunnable action) {
        try {
            action.run();
        } catch (Throwable thrown) {
            if (!expectedType.isInstance(thrown)) {
                throw new AssertionError("expected <" + expectedType.getSimpleName()
                        + "> but got <" + thrown.getClass().getSimpleName() + ">", thrown);
            }
            return;
        }
        throw new AssertionError("expected <" + expectedType.getSimpleName()
                + "> but nothing was thrown");
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
        private volatile long now;

        private MutableTime(long now) {
            this.now = now;
        }

        @Override
        public long nowMillis() {
            return now;
        }

        private void set(long now) {
            this.now = now;
        }

        private void advance(long millis) {
            now += millis;
        }
    }
}
