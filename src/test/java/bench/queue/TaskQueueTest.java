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
        run("due-time ordering and delayed retry", TaskQueueTest::orderingAndRetryDelay);
        run("earliest due time wins and ties remain FIFO", TaskQueueTest::dueTimeOrderingAndFifo);
        run("lease expiry rejects stale worker and exhausts attempts", TaskQueueTest::leaseExpiry);
        run("acknowledged task is terminal", TaskQueueTest::acknowledgement);
        run("cancellation invalidates active lease", TaskQueueTest::cancellation);
        run("concurrent claims do not duplicate tasks", TaskQueueTest::concurrentClaims);
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
