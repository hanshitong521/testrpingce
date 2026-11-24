package bench.commerce;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

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
        run("paymentAmountMismatchDoesNotReservePaymentId", CommerceTest::paymentAmountMismatchDoesNotReservePaymentId);
        run("paymentIdReuseAcrossOrdersIsConflict", CommerceTest::paymentIdReuseAcrossOrdersIsConflict);
        run("expiredOrderLatePaymentRequiresRefund", CommerceTest::expiredOrderLatePaymentRequiresRefund);
        run("duplicateCallbackAfterPartialRefundIsCompensated", CommerceTest::duplicateCallbackAfterPartialRefundIsCompensated);
        run("fullRefundDoesNotRestockOrReleaseCoupon", CommerceTest::fullRefundDoesNotRestockOrReleaseCoupon);
        run("refundIdReuseIsConflictWithoutSideEffects", CommerceTest::refundIdReuseIsConflictWithoutSideEffects);
        run("repeatedExpirationReleasesInventoryOnce", CommerceTest::repeatedExpirationReleasesInventoryOnce);
        run("concurrentCallbacksAndExpirationCommitOnce", CommerceTest::concurrentCallbacksAndExpirationCommitOnce);
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

    private static void paymentAmountMismatchDoesNotReservePaymentId() {
        CommerceSystem app = app(new ManualTime(12_000));
        app.addStock("A", 1);
        app.createOrder("O-AMOUNT", List.of(new OrderItem("A", 1, 900)), null, 10_000);
        eq(CallbackResult.REJECTED, app.paymentSucceeded("P-AMOUNT", "O-AMOUNT", 899), "wrong amount rejected");
        eq(OrderStatus.PENDING_PAYMENT, app.order("O-AMOUNT").status(), "order unchanged");
        eq(CallbackResult.ACCEPTED, app.paymentSucceeded("P-AMOUNT", "O-AMOUNT", 900), "same id accepted with valid amount");
        eq(1, app.soldStock("A"), "stock committed once");
    }

    private static void paymentIdReuseAcrossOrdersIsConflict() {
        CommerceSystem app = app(new ManualTime(13_000));
        app.addStock("A", 2);
        app.createOrder("O-P1", List.of(new OrderItem("A", 1, 300)), null, 10_000);
        app.createOrder("O-P2", List.of(new OrderItem("A", 1, 300)), null, 10_000);
        eq(CallbackResult.ACCEPTED, app.paymentSucceeded("P-SHARED", "O-P1", 300), "first callback accepted");
        eq(CallbackResult.CONFLICT, app.paymentSucceeded("P-SHARED", "O-P2", 300), "payment id reuse conflict");
        eq(OrderStatus.PENDING_PAYMENT, app.order("O-P2").status(), "second order unchanged");
        eq(1, app.soldStock("A"), "inventory unchanged by conflict");
    }

    private static void expiredOrderLatePaymentRequiresRefund() {
        ManualTime clock = new ManualTime(14_000);
        CommerceSystem app = app(clock);
        app.addStock("A", 1);
        app.addCoupon("EXP", 50);
        app.createOrder("O-EXP", List.of(new OrderItem("A", 1, 500)), "EXP", 100);
        clock.set(14_100);
        eq(1, app.expireDueOrders(), "order expired");
        eq(CallbackResult.REFUND_REQUIRED, app.paymentSucceeded("P-EXP", "O-EXP", 450), "late payment compensated");
        eq(OrderStatus.CANCELLED, app.order("O-EXP").status(), "expired order stays cancelled");
        eq(1, app.availableStock("A"), "stock stays released");
        eq(CouponStatus.AVAILABLE, app.couponStatus("EXP"), "coupon stays available");
        eq(1L, countEvents(app, "PAYMENT_REFUND_REQUIRED"), "refund-required audit event recorded");
    }

    private static void duplicateCallbackAfterPartialRefundIsCompensated() {
        CommerceSystem app = app(new ManualTime(15_000));
        app.addStock("A", 1);
        app.createOrder("O-PR", List.of(new OrderItem("A", 1, 700)), null, 10_000);
        app.paymentSucceeded("P-PR", "O-PR", 700);
        check(app.refund("R-PR", "O-PR", 200).accepted(), "partial refund accepted");
        eq(CallbackResult.REFUND_REQUIRED, app.paymentSucceeded("P-PR", "O-PR", 700),
                "callback after refund requires compensation");
        eq(CallbackResult.REFUND_REQUIRED, app.paymentSucceeded("P-PR", "O-PR", 700),
                "compensation callback replay stays stable");
        eq(700L, app.order("O-PR").paidCents(), "paid amount unchanged");
        eq(200L, app.order("O-PR").refundedCents(), "refund total unchanged");
        eq(1, app.soldStock("A"), "inventory not changed by duplicate callback");
        eq(1L, countEvents(app, "PAYMENT_REFUND_REQUIRED"), "one compensation event");
    }

    private static void fullRefundDoesNotRestockOrReleaseCoupon() {
        CommerceSystem app = app(new ManualTime(16_000));
        app.addStock("A", 2);
        app.addCoupon("FULL", 100);
        app.createOrder("O-FULL", List.of(new OrderItem("A", 2, 400)), "FULL", 10_000);
        app.paymentSucceeded("P-FULL", "O-FULL", 700);
        check(app.refund("R-FULL", "O-FULL", 700).accepted(), "full refund accepted");
        eq(OrderStatus.REFUNDED, app.order("O-FULL").status(), "order refunded");
        eq(0, app.availableStock("A"), "refund does not restock");
        eq(2, app.soldStock("A"), "sold quantity unchanged");
        eq(CouponStatus.USED, app.couponStatus("FULL"), "used coupon not released");
    }

    private static void refundIdReuseIsConflictWithoutSideEffects() {
        CommerceSystem app = app(new ManualTime(17_000));
        app.addStock("A", 1);
        app.createOrder("O-RC", List.of(new OrderItem("A", 1, 600)), null, 10_000);
        app.paymentSucceeded("P-RC", "O-RC", 600);
        check(app.refund("R-CONFLICT", "O-RC", 200).accepted(), "first refund accepted");
        RefundResult conflict = app.refund("R-CONFLICT", "O-RC", 300);
        check(!conflict.accepted() && "refund_id_conflict".equals(conflict.reason()), "conflict reported");
        eq(200L, app.order("O-RC").refundedCents(), "refund total unchanged");
        eq(1L, countEvents(app, "REFUND_SUCCEEDED"), "no extra refund event");
    }

    private static void repeatedExpirationReleasesInventoryOnce() {
        ManualTime clock = new ManualTime(18_000);
        CommerceSystem app = app(clock);
        app.addStock("A", 3);
        app.createOrder("O-REPEAT", List.of(new OrderItem("A", 2, 100)), null, 100);
        clock.set(18_100);
        eq(1, app.expireDueOrders(), "first expiration releases order");
        eq(0, app.expireDueOrders(), "second expiration is a no-op");
        eq(3, app.availableStock("A"), "inventory released exactly once");
        eq(0, app.reservedStock("A"), "no reservation remains");
        eq(1L, countEvents(app, "ORDER_CANCELLED"), "one cancellation event");
    }

    private static void concurrentCallbacksAndExpirationCommitOnce() {
        ManualTime clock = new ManualTime(19_000);
        CommerceSystem app = app(clock);
        app.addStock("A", 1);
        app.createOrder("O-RACE", List.of(new OrderItem("A", 1, 1_000)), null, 100);
        clock.set(19_100);
        ExecutorService executor = Executors.newFixedThreadPool(4);
        try {
            CountDownLatch start = new CountDownLatch(1);
            Future<?> payment = executor.submit(() -> awaitAndRun(start,
                    () -> app.paymentSucceeded("P-RACE", "O-RACE", 1_000)));
            Future<?> duplicate = executor.submit(() -> awaitAndRun(start,
                    () -> app.paymentSucceeded("P-RACE", "O-RACE", 1_000)));
            Future<?> expiration = executor.submit(() -> awaitAndRun(start, app::expireDueOrders));
            Future<?> cancellation = executor.submit(() -> awaitAndRun(start, () -> app.cancelOrder("O-RACE")));
            start.countDown();
            payment.get(5, TimeUnit.SECONDS);
            duplicate.get(5, TimeUnit.SECONDS);
            expiration.get(5, TimeUnit.SECONDS);
            cancellation.get(5, TimeUnit.SECONDS);
        } catch (Exception error) {
            throw new AssertionError("concurrent scenario did not finish", error);
        } finally {
            executor.shutdownNow();
        }
        OrderStatus finalStatus = app.order("O-RACE").status();
        check(finalStatus == OrderStatus.PAID || finalStatus == OrderStatus.CANCELLED,
                "race ends in one committed order state");
        int accounted = app.availableStock("A") + app.reservedStock("A") + app.soldStock("A");
        eq(1, accounted, "inventory buckets always sum to stock");
        long successEvents = countEvents(app, "PAYMENT_SUCCEEDED");
        eq(finalStatus == OrderStatus.PAID ? 1L : 0L, successEvents, "success events match final state");
    }

    private static void awaitAndRun(CountDownLatch start, Runnable action) {
        try {
            start.await();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted before action", error);
        }
        action.run();
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
