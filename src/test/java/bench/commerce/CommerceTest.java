package bench.commerce;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

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
