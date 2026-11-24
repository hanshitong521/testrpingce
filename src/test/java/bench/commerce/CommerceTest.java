package bench.commerce;

import java.util.List;
import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;

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
        run("paymentAmountMismatchIsRetryable", CommerceTest::paymentAmountMismatchIsRetryable);
        run("reusedPaymentIdWithOtherOrderIsConflict", CommerceTest::reusedPaymentIdWithOtherOrderIsConflict);
        run("secondDistinctPaymentRequiresRefund", CommerceTest::secondDistinctPaymentRequiresRefund);
        run("latePaymentEmitsCompensationEvent", CommerceTest::latePaymentEmitsCompensationEvent);
        run("expirationDoesNotExpireLateAtBoundary", CommerceTest::expirationDoesNotExpireLateAtBoundary);
        run("duplicateCancelAndExpireReleaseInventoryOnce", CommerceTest::duplicateCancelAndExpireReleaseInventoryOnce);
        run("couponCannotBeSharedByTwoOrders", CommerceTest::couponCannotBeSharedByTwoOrders);
        run("refundIdConflictDoesNotMutateState", CommerceTest::refundIdConflictDoesNotMutateState);
        run("fullRefundDoesNotReopenCouponOrInventory", CommerceTest::fullRefundDoesNotReopenCouponOrInventory);
        run("refundRejectsNonPositiveAmount", CommerceTest::refundRejectsNonPositiveAmount);
        run("concurrentPaymentCommitsInventoryOnce", CommerceTest::concurrentPaymentCommitsInventoryOnce);
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

    private static void paymentAmountMismatchIsRetryable() {
        CommerceSystem app = app(new ManualTime(12_000));
        app.addStock("A", 1);
        app.createOrder("O-AMOUNT", List.of(new OrderItem("A", 1, 500)), null, 5_000);
        eq(CallbackResult.REJECTED, app.paymentSucceeded("P-AMOUNT", "O-AMOUNT", 400), "bad amount rejected");
        eq(OrderStatus.PENDING_PAYMENT, app.order("O-AMOUNT").status(), "order unchanged");
        eq(0, app.availableStock("A"), "reservation unchanged after mismatch");
        eq(CallbackResult.ACCEPTED, app.paymentSucceeded("P-AMOUNT", "O-AMOUNT", 500), "retry accepted");
        eq(1, app.soldStock("A"), "inventory committed once");
        eq(1L, countEvents(app, "PAYMENT_SUCCEEDED"), "one successful payment event");
    }

    private static void reusedPaymentIdWithOtherOrderIsConflict() {
        CommerceSystem app = app(new ManualTime(13_000));
        app.addStock("A", 1);
        app.addStock("B", 1);
        app.createOrder("O-A", List.of(new OrderItem("A", 1, 200)), null, 5_000);
        app.createOrder("O-B", List.of(new OrderItem("B", 1, 300)), null, 5_000);
        eq(CallbackResult.ACCEPTED, app.paymentSucceeded("P-SHARED", "O-A", 200), "first payment");
        eq(CallbackResult.CONFLICT, app.paymentSucceeded("P-SHARED", "O-B", 200), "payment id conflict");
        eq(OrderStatus.PENDING_PAYMENT, app.order("O-B").status(), "other order unchanged");
        eq(0, app.soldStock("B"), "other order inventory unchanged");
    }

    private static void secondDistinctPaymentRequiresRefund() {
        CommerceSystem app = app(new ManualTime(14_000));
        app.addStock("A", 1);
        app.createOrder("O-DOUBLE", List.of(new OrderItem("A", 1, 700)), null, 5_000);
        eq(CallbackResult.ACCEPTED, app.paymentSucceeded("P-FIRST", "O-DOUBLE", 700), "first payment");
        eq(CallbackResult.REFUND_REQUIRED, app.paymentSucceeded("P-SECOND", "O-DOUBLE", 700), "second capture");
        eq(1, app.soldStock("A"), "inventory committed once");
        eq(1L, countEvents(app, "PAYMENT_REFUND_REQUIRED"), "compensation event emitted");
    }

    private static void latePaymentEmitsCompensationEvent() {
        CommerceSystem app = app(new ManualTime(15_000));
        app.addStock("A", 2);
        app.addCoupon("LATE", 30);
        app.createOrder("O-LATE", List.of(new OrderItem("A", 1, 200)), "LATE", 100);
        check(app.cancelOrder("O-LATE"), "cancel before payment");
        eq(CallbackResult.REFUND_REQUIRED, app.paymentSucceeded("P-LATE", "O-LATE", 170), "late capture");
        eq(CouponStatus.AVAILABLE, app.couponStatus("LATE"), "coupon stays released");
        eq(0, app.soldStock("A"), "late payment does not commit stock");
        eq(1L, countEvents(app, "PAYMENT_REFUND_REQUIRED"), "one compensation event");
    }

    private static void expirationDoesNotExpireLateAtBoundary() {
        ManualTime clock = new ManualTime(16_000);
        CommerceSystem app = app(clock);
        app.addStock("A", 2);
        app.createOrder("O-BOUNDARY", List.of(new OrderItem("A", 1, 100)), null, 100);
        clock.set(16_099);
        eq(0, app.expireDueOrders(), "not due one millisecond early");
        clock.set(16_100);
        eq(1, app.expireDueOrders(), "due exactly at expiresAt");
        eq(OrderStatus.CANCELLED, app.order("O-BOUNDARY").status(), "boundary order cancelled");
    }

    private static void duplicateCancelAndExpireReleaseInventoryOnce() {
        ManualTime clock = new ManualTime(17_000);
        CommerceSystem app = app(clock);
        app.addStock("A", 2);
        app.addCoupon("CANCEL", 10);
        app.createOrder("O-ONCE", List.of(new OrderItem("A", 1, 100)), "CANCEL", 100);
        clock.set(17_100);
        eq(1, app.expireDueOrders(), "expiration cancels pending order");
        eq(2, app.availableStock("A"), "reservation released once");
        eq(0, app.reservedStock("A"), "no reservation remains");
        eq(CouponStatus.AVAILABLE, app.couponStatus("CANCEL"), "coupon released once");
        check(!app.cancelOrder("O-ONCE"), "manual cancel after expiry is no-op");
        eq(2, app.availableStock("A"), "duplicate release does not inflate stock");
        eq(1L, countEvents(app, "ORDER_CANCELLED"), "one cancellation event");
    }

    private static void couponCannotBeSharedByTwoOrders() {
        CommerceSystem app = app(new ManualTime(18_000));
        app.addStock("A", 1);
        app.addStock("B", 1);
        app.addCoupon("ONLY", 25);
        app.createOrder("O-COUPON", List.of(new OrderItem("A", 1, 100)), "ONLY", 5_000);
        expect(IllegalStateException.class, () -> app.createOrder("O-SECOND",
                List.of(new OrderItem("B", 1, 100)), "ONLY", 5_000), "reserved coupon cannot be reused");
        eq(1, app.availableStock("B"), "failed second order does not reserve stock");
        eq(1, app.reservedStock("A"), "first order reservation intact");
    }

    private static void refundIdConflictDoesNotMutateState() {
        CommerceSystem app = app(new ManualTime(19_000));
        app.addStock("A", 1);
        app.createOrder("O-CONFLICT", List.of(new OrderItem("A", 1, 1_000)), null, 5_000);
        eq(CallbackResult.ACCEPTED, app.paymentSucceeded("P-CONFLICT", "O-CONFLICT", 1_000), "payment");
        check(app.refund("R-SAME", "O-CONFLICT", 300).accepted(), "first partial refund");
        RefundResult conflict = app.refund("R-SAME", "O-CONFLICT", 400);
        check(!conflict.accepted() && !conflict.duplicate(), "refund id conflict rejected");
        eq(300L, app.order("O-CONFLICT").refundedCents(), "refund total unchanged");
        eq(1L, countEvents(app, "REFUND_SUCCEEDED"), "no extra refund event");
    }

    private static void fullRefundDoesNotReopenCouponOrInventory() {
        CommerceSystem app = app(new ManualTime(20_000));
        app.addStock("A", 1);
        app.addCoupon("USED-ONCE", 100);
        app.createOrder("O-FULL", List.of(new OrderItem("A", 1, 300)), "USED-ONCE", 5_000);
        eq(CallbackResult.ACCEPTED, app.paymentSucceeded("P-FULL", "O-FULL", 200), "payment");
        RefundResult full = app.refund("R-FULL", "O-FULL", 200);
        check(full.accepted(), "full refund accepted");
        eq(OrderStatus.REFUNDED, app.order("O-FULL").status(), "order fully refunded");
        eq(CouponStatus.USED, app.couponStatus("USED-ONCE"), "used coupon stays consumed");
        eq(1, app.soldStock("A"), "refund does not restock");
        check(!app.refund("R-MORE", "O-FULL", 1).accepted(), "refund after full refund rejected");
    }

    private static void refundRejectsNonPositiveAmount() {
        CommerceSystem app = app(new ManualTime(21_000));
        app.addStock("A", 1);
        app.createOrder("O-ZERO", List.of(new OrderItem("A", 1, 100)), null, 5_000);
        eq(CallbackResult.ACCEPTED, app.paymentSucceeded("P-ZERO", "O-ZERO", 100), "payment");
        check(!app.refund("R-ZERO", "O-ZERO", 0).accepted(), "zero refund rejected");
        check(!app.refund("R-NEG", "O-ZERO", -1).accepted(), "negative refund rejected");
        eq(0L, app.order("O-ZERO").refundedCents(), "refund total unchanged");
    }

    private static void concurrentPaymentCommitsInventoryOnce() {
        ManualTime clock = new ManualTime(22_000);
        CommerceSystem app = app(clock);
        app.addStock("A", 1);
        app.createOrder("O-RACE", List.of(new OrderItem("A", 1, 900)), null, 5_000);
        int threads = 8;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        List<CallbackResult> results = Collections.synchronizedList(new ArrayList<>());
        List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());
        for (int index = 0; index < threads; index++) {
            String paymentId = "P-RACE-" + index;
            new Thread(() -> {
                try {
                    start.await();
                    results.add(app.paymentSucceeded(paymentId, "O-RACE", 900));
                } catch (Throwable error) {
                    failures.add(error);
                } finally {
                    done.countDown();
                }
            }).start();
        }
        start.countDown();
        try {
            check(done.await(5, java.util.concurrent.TimeUnit.SECONDS), "concurrent callbacks completed");
        } catch (InterruptedException error) {
            throw new AssertionError(error);
        }
        check(failures.isEmpty(), "no concurrent callback errors");
        eq(1L, results.stream().filter(CallbackResult.ACCEPTED::equals).count(), "exactly one payment accepted");
        eq(1, app.soldStock("A"), "inventory sold once");
        eq(0, app.reservedStock("A"), "reservation committed once");
        eq(1L, countEvents(app, "PAYMENT_SUCCEEDED"), "one success event");
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
