package bench.commerce;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public final class CommerceTest {
    private static int passed;
    private static int failed;

    public static void main(String[] args) {
        run("checkoutAndPaymentHappyPath", CommerceTest::checkoutAndPaymentHappyPath);
        run("manualCancellationReleasesAllLinesAndCoupon", CommerceTest::manualCancellationReleasesAllLinesAndCoupon);
        run("latePaymentMustNotResurrectCancelledOrder", CommerceTest::latePaymentMustNotResurrectCancelledOrder);
        run("duplicatePaymentCallbackMustBeIdempotent", CommerceTest::duplicatePaymentCallbackMustBeIdempotent);
        run("refundReplayAndCumulativeLimit", CommerceTest::refundReplayAndCumulativeLimit);
        run("expirationCancelsOnlyDueOrders", CommerceTest::expirationCancelsOnlyDueOrders);
        run("failedCreationMustNotStrandCoupon", CommerceTest::failedCreationMustNotStrandCoupon);
        run("amountMismatchDoesNotConsumePaymentId", CommerceTest::amountMismatchDoesNotConsumePaymentId);
        run("paymentIdReuseWithDifferentPayloadIsConflict", CommerceTest::paymentIdReuseWithDifferentPayloadIsConflict);
        run("secondDistinctPaymentRequiresRefund", CommerceTest::secondDistinctPaymentRequiresRefund);
        run("expiredOrderLatePaymentRequiresRefund", CommerceTest::expiredOrderLatePaymentRequiresRefund);
        run("refundIdReuseConflictDoesNotMutateTotals", CommerceTest::refundIdReuseConflictDoesNotMutateTotals);
        run("partialRefundsReachExactFullAmount", CommerceTest::partialRefundsReachExactFullAmount);
        run("couponCannotBeReservedByTwoOrders", CommerceTest::couponCannotBeReservedByTwoOrders);
        run("paymentExpirationRaceCommitsExactlyOnce", CommerceTest::paymentExpirationRaceCommitsExactlyOnce);
        run("concurrentDuplicateCallbacksCommitOnce", CommerceTest::concurrentDuplicateCallbacksCommitOnce);
        System.out.println("RESULT: " + passed + " passed, " + failed + " failed");
        if (failed != 0) {
            throw new AssertionError("visible commerce tests failed: " + failed);
        }
    }

    private static void checkoutAndPaymentHappyPath() {
        ManualTime clock = new ManualTime(1_000);
        CommerceSystem app = app(clock);
        app.addStock("A", 5);
        app.addStock("B", 3);
        app.addCoupon("SAVE10", 100);
        OrderView order = app.createOrder("O-1", List.of(
                new OrderItem("A", 2, 500), new OrderItem("B", 1, 300)), "SAVE10", 10_000);
        eq(1_200L, order.payableCents(), "payable amount");
        eq(3, app.availableStock("A"), "A available after reserve");
        eq(2, app.reservedStock("A"), "A reserved");
        eq(CallbackResult.ACCEPTED, app.paymentSucceeded("P-1", "O-1", 1_200), "payment result");
        eq(OrderStatus.PAID, app.order("O-1").status(), "order paid");
        eq(0, app.reservedStock("A"), "A no longer reserved");
        eq(2, app.soldStock("A"), "A sold");
        eq(CouponStatus.USED, app.couponStatus("SAVE10"), "coupon consumed");
    }

    private static void manualCancellationReleasesAllLinesAndCoupon() {
        CommerceSystem app = app(new ManualTime(2_000));
        app.addStock("A", 5);
        app.addStock("B", 4);
        app.addCoupon("SAVE", 50);
        app.createOrder("O-2", List.of(new OrderItem("A", 2, 100), new OrderItem("B", 3, 100)), "SAVE", 5_000);
        check(app.cancelOrder("O-2"), "first cancellation should succeed");
        check(!app.cancelOrder("O-2"), "second cancellation should be a no-op");
        eq(5, app.availableStock("A"), "A restored");
        eq(4, app.availableStock("B"), "B restored");
        eq(0, app.reservedStock("A"), "A reservation cleared");
        eq(CouponStatus.AVAILABLE, app.couponStatus("SAVE"), "coupon released");
    }

    private static void latePaymentMustNotResurrectCancelledOrder() {
        CommerceSystem app = app(new ManualTime(3_000));
        app.addStock("A", 2);
        app.createOrder("O-3", List.of(new OrderItem("A", 1, 700)), null, 100);
        check(app.cancelOrder("O-3"), "cancel should succeed");
        eq(CallbackResult.REFUND_REQUIRED, app.paymentSucceeded("P-LATE", "O-3", 700), "late payment requires compensation");
        eq(OrderStatus.CANCELLED, app.order("O-3").status(), "cancelled order must stay cancelled");
        eq(2, app.availableStock("A"), "late callback must not reacquire stock");
        eq(0, app.reservedStock("A"), "no reservation remains");
        eq(0, app.soldStock("A"), "no inventory commit");
    }

    private static void duplicatePaymentCallbackMustBeIdempotent() {
        CommerceSystem app = app(new ManualTime(4_000));
        app.addStock("A", 2);
        app.createOrder("O-4", List.of(new OrderItem("A", 1, 800)), null, 5_000);
        eq(CallbackResult.ACCEPTED, app.paymentSucceeded("P-4", "O-4", 800), "first callback");
        eq(CallbackResult.DUPLICATE, app.paymentSucceeded("P-4", "O-4", 800), "callback replay");
        eq(0, app.reservedStock("A"), "reservation remains committed once");
        eq(1, app.soldStock("A"), "sold only once");
        eq(1L, countEvents(app, "PAYMENT_SUCCEEDED"), "one success event");
    }

    private static void refundReplayAndCumulativeLimit() {
        CommerceSystem app = app(new ManualTime(5_000));
        app.addStock("A", 1);
        app.createOrder("O-5", List.of(new OrderItem("A", 1, 1_000)), null, 5_000);
        eq(CallbackResult.ACCEPTED, app.paymentSucceeded("P-5", "O-5", 1_000), "payment");
        RefundResult first = app.refund("R-1", "O-5", 600);
        check(first.accepted(), "first partial refund accepted");
        RefundResult replay = app.refund("R-1", "O-5", 600);
        check(replay.accepted() && replay.duplicate(), "same refund request is idempotent");
        RefundResult tooMuch = app.refund("R-2", "O-5", 500);
        check(!tooMuch.accepted(), "cumulative refund cannot exceed capture");
        eq(600L, app.order("O-5").refundedCents(), "refund total remains 600");
        eq(OrderStatus.PARTIALLY_REFUNDED, app.order("O-5").status(), "partial refund state");
    }

    private static void expirationCancelsOnlyDueOrders() {
        ManualTime clock = new ManualTime(10_000);
        CommerceSystem app = app(clock);
        app.addStock("A", 4);
        app.createOrder("O-DUE", List.of(new OrderItem("A", 1, 100)), null, 50);
        app.createOrder("O-LIVE", List.of(new OrderItem("A", 1, 100)), null, 500);
        clock.set(10_050);
        eq(1, app.expireDueOrders(), "only due order expires");
        eq(OrderStatus.CANCELLED, app.order("O-DUE").status(), "due order cancelled");
        eq(OrderStatus.PENDING_PAYMENT, app.order("O-LIVE").status(), "not-yet-due order stays pending");
    }

    private static void failedCreationMustNotStrandCoupon() {
        CommerceSystem app = app(new ManualTime(11_000));
        app.addStock("A", 1);
        app.addCoupon("ROLLBACK", 100);
        expect(IllegalStateException.class, () -> app.createOrder("O-FAIL",
                List.of(new OrderItem("A", 2, 100)), "ROLLBACK", 100), "insufficient stock");
        eq(CouponStatus.AVAILABLE, app.couponStatus("ROLLBACK"), "coupon reservation rolled back");
        eq(1, app.availableStock("A"), "stock remains unchanged");
    }

    private static void amountMismatchDoesNotConsumePaymentId() {
        CommerceSystem app = app(new ManualTime(20_000));
        app.addStock("A", 2);
        app.createOrder("O-M", List.of(new OrderItem("A", 1, 500)), null, 5_000);
        eq(CallbackResult.REJECTED, app.paymentSucceeded("P-M", "O-M", 499), "mismatch rejected");
        eq(OrderStatus.PENDING_PAYMENT, app.order("O-M").status(), "mismatch leaves order pending");
        eq(1, app.reservedStock("A"), "reservation untouched");
        eq(0L, countEvents(app, "PAYMENT_SUCCEEDED"), "no success event");
        eq(CallbackResult.ACCEPTED, app.paymentSucceeded("P-M", "O-M", 500), "same id retry with valid amount");
        eq(1, app.soldStock("A"), "committed exactly once");
    }

    private static void paymentIdReuseWithDifferentPayloadIsConflict() {
        CommerceSystem app = app(new ManualTime(21_000));
        app.addStock("A", 1);
        app.addStock("B", 1);
        app.createOrder("O-X", List.of(new OrderItem("A", 1, 400)), null, 5_000);
        app.createOrder("O-Y", List.of(new OrderItem("B", 1, 400)), null, 5_000);
        eq(CallbackResult.ACCEPTED, app.paymentSucceeded("P-X", "O-X", 400), "first order paid");
        eq(CallbackResult.CONFLICT, app.paymentSucceeded("P-X", "O-Y", 400), "payment id reuse conflict");
        eq(OrderStatus.PENDING_PAYMENT, app.order("O-Y").status(), "other order stays pending");
        eq(0, app.availableStock("B"), "other reservation untouched");
    }

    private static void secondDistinctPaymentRequiresRefund() {
        CommerceSystem app = app(new ManualTime(22_000));
        app.addStock("A", 1);
        app.createOrder("O-2P", List.of(new OrderItem("A", 1, 900)), null, 5_000);
        eq(CallbackResult.ACCEPTED, app.paymentSucceeded("P-1", "O-2P", 900), "first capture");
        eq(CallbackResult.REFUND_REQUIRED, app.paymentSucceeded("P-2", "O-2P", 900), "second capture compensated");
        eq(1, app.soldStock("A"), "stock committed once");
        eq(1L, countEvents(app, "PAYMENT_SUCCEEDED"), "one success event");
        eq(1L, countEvents(app, "PAYMENT_REFUND_REQUIRED"), "one compensation event");
    }

    private static void expiredOrderLatePaymentRequiresRefund() {
        ManualTime clock = new ManualTime(23_000);
        CommerceSystem app = app(clock);
        app.addCoupon("EXP", 50);
        app.addStock("A", 2);
        app.createOrder("O-E", List.of(new OrderItem("A", 1, 600)), "EXP", 100);
        clock.set(23_100);
        eq(1, app.expireDueOrders(), "expiration runs");
        eq(CouponStatus.AVAILABLE, app.couponStatus("EXP"), "coupon released on expiry");
        eq(CallbackResult.REFUND_REQUIRED, app.paymentSucceeded("P-E", "O-E", 550), "late capture compensated");
        eq(OrderStatus.CANCELLED, app.order("O-E").status(), "expired order stays cancelled");
        eq(2, app.availableStock("A"), "no stock reacquired");
        eq(0, app.reservedStock("A"), "no reservation remains");
        eq(0, app.soldStock("A"), "no sale recorded");
        eq(CouponStatus.AVAILABLE, app.couponStatus("EXP"), "coupon not consumed by late capture");
    }

    private static void refundIdReuseConflictDoesNotMutateTotals() {
        CommerceSystem app = app(new ManualTime(24_000));
        app.addStock("A", 1);
        app.createOrder("O-RC", List.of(new OrderItem("A", 1, 800)), null, 5_000);
        app.paymentSucceeded("P-RC", "O-RC", 800);
        check(app.refund("R-RC", "O-RC", 300).accepted(), "first refund accepted");
        RefundResult conflict = app.refund("R-RC", "O-RC", 400);
        check(!conflict.accepted() && "refund_id_conflict".equals(conflict.reason()), "refund id conflict");
        eq(300L, app.order("O-RC").refundedCents(), "refund total unchanged");
        eq(1L, countEvents(app, "REFUND_SUCCEEDED"), "no duplicate refund event");
    }

    private static void partialRefundsReachExactFullAmount() {
        CommerceSystem app = app(new ManualTime(25_000));
        app.addStock("A", 1);
        app.createOrder("O-PR", List.of(new OrderItem("A", 1, 1_000)), null, 5_000);
        app.paymentSucceeded("P-PR", "O-PR", 1_000);
        check(app.refund("R-1", "O-PR", 400).accepted(), "first partial");
        check(app.refund("R-2", "O-PR", 600).accepted(), "second partial completes refund");
        eq(OrderStatus.REFUNDED, app.order("O-PR").status(), "fully refunded");
        check(!app.refund("R-3", "O-PR", 1).accepted(), "no refund beyond captured amount");
        eq(1_000L, app.order("O-PR").refundedCents(), "cumulative total exact");
        eq(1, app.soldStock("A"), "refund does not restock");
    }

    private static void couponCannotBeReservedByTwoOrders() {
        CommerceSystem app = app(new ManualTime(26_000));
        app.addStock("A", 3);
        app.addCoupon("ONE", 100);
        app.createOrder("O-C1", List.of(new OrderItem("A", 1, 500)), "ONE", 5_000);
        expect(IllegalStateException.class,
                () -> app.createOrder("O-C2", List.of(new OrderItem("A", 1, 500)), "ONE", 5_000),
                "coupon cannot be reused while reserved");
        eq(CouponStatus.RESERVED, app.couponStatus("ONE"), "coupon stays reserved by first order");
        eq(2, app.availableStock("A"), "failed order did not reserve stock");
    }

    private static void paymentExpirationRaceCommitsExactlyOnce() {
        ManualTime clock = new ManualTime(27_000);
        CommerceSystem app = app(clock);
        app.addStock("A", 1);
        app.createOrder("O-RACE", List.of(new OrderItem("A", 1, 700)), null, 100);
        clock.set(27_100);
        race(() -> app.paymentSucceeded("P-RACE", "O-RACE", 700), app::expireDueOrders);
        OrderStatus status = app.order("O-RACE").status();
        check(status == OrderStatus.PAID || status == OrderStatus.CANCELLED, "race reaches one valid terminal state");
        eq(0, app.reservedStock("A"), "reservation is settled once");
        eq(status == OrderStatus.PAID ? 1 : 0, app.soldStock("A"), "stock committed only if paid");
        eq(status == OrderStatus.PAID ? 0 : 1, app.availableStock("A"), "stock released only if cancelled");
    }

    private static void concurrentDuplicateCallbacksCommitOnce() {
        CommerceSystem app = app(new ManualTime(28_000));
        app.addStock("A", 1);
        app.createOrder("O-DUP", List.of(new OrderItem("A", 1, 650)), null, 5_000);
        List<CallbackResult> results = new ArrayList<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        Thread[] threads = new Thread[8];
        CountDownLatch ready = new CountDownLatch(threads.length);
        CountDownLatch start = new CountDownLatch(1);
        for (int i = 0; i < threads.length; i++) {
            threads[i] = new Thread(() -> {
                ready.countDown();
                try {
                    start.await();
                    synchronized (results) {
                        results.add(app.paymentSucceeded("P-DUP", "O-DUP", 650));
                    }
                } catch (Throwable failure) {
                    error.compareAndSet(null, failure);
                }
            });
            threads[i].start();
        }
        await(ready, "workers ready");
        start.countDown();
        for (Thread thread : threads) {
            join(thread, "worker finished");
        }
        if (error.get() != null) {
            throw new AssertionError("worker failed", error.get());
        }
        eq(1L, results.stream().filter(CallbackResult.ACCEPTED::equals).count(), "one acceptance");
        eq(threads.length - 1L, results.stream().filter(CallbackResult.DUPLICATE::equals).count(), "rest duplicate");
        eq(1, app.soldStock("A"), "sold exactly once");
        eq(1L, countEvents(app, "PAYMENT_SUCCEEDED"), "one success event");
    }

    private static void race(Runnable first, Runnable second) {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        Thread a = new Thread(() -> awaitAndRun(ready, start, first, error));
        Thread b = new Thread(() -> awaitAndRun(ready, start, second, error));
        a.start();
        b.start();
        await(ready, "race workers ready");
        start.countDown();
        join(a, "race worker a finished");
        join(b, "race worker b finished");
        if (error.get() != null) {
            throw new AssertionError("race worker failed", error.get());
        }
    }

    private static void await(CountDownLatch latch, String message) {
        try {
            check(latch.await(5, TimeUnit.SECONDS), message);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError(message, failure);
        }
    }

    private static void join(Thread thread, String message) {
        try {
            thread.join(5_000);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError(message, failure);
        }
        check(!thread.isAlive(), message);
    }

    private static void awaitAndRun(CountDownLatch ready, CountDownLatch start, Runnable action,
                                    AtomicReference<Throwable> error) {
        ready.countDown();
        try {
            start.await();
            action.run();
        } catch (Throwable failure) {
            error.compareAndSet(null, failure);
        }
    }

    private static CommerceSystem app(ManualTime clock) {
        return new CommerceSystem(clock);
    }

    private static long countEvents(CommerceSystem app, String type) {
        return app.events().stream().filter(event -> event.type().equals(type)).count();
    }

    private static void run(String name, Runnable test) {
        try {
            test.run();
            passed++;
            System.out.println("PASS " + name);
        } catch (Throwable error) {
            failed++;
            System.out.println("FAIL " + name + ": " + error.getMessage());
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void eq(Object expected, Object actual, String message) {
        if (!expected.equals(actual)) {
            throw new AssertionError(message + " expected <" + expected + "> but was <" + actual + ">");
        }
    }

    private static void expect(Class<? extends Throwable> type, Runnable action, String message) {
        try {
            action.run();
        } catch (Throwable error) {
            if (type.isInstance(error)) {
                return;
            }
            throw new AssertionError(message + ": wrong exception " + error, error);
        }
        throw new AssertionError(message + ": expected " + type.getSimpleName());
    }

    private static final class ManualTime implements TimeSource {
        private volatile long now;
        ManualTime(long now) { this.now = now; }
        @Override public long nowMillis() { return now; }
        void set(long value) { now = value; }
    }
}
