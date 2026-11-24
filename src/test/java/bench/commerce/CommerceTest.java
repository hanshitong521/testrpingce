package bench.commerce;

import java.util.ArrayList;
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
        run("expirationRacingPaymentCommitsExactlyOnce", CommerceTest::expirationRacingPaymentCommitsExactlyOnce);
        run("paymentIdConflictDoesNotMutateOrders", CommerceTest::paymentIdConflictDoesNotMutateOrders);
        run("wrongAmountCanBeRetriedWithSamePaymentId", CommerceTest::wrongAmountCanBeRetriedWithSamePaymentId);
        run("secondDistinctPaymentForPaidOrderRequiresRefund", CommerceTest::secondDistinctPaymentForPaidOrderRequiresRefund);
        run("refundIdConflictAndInvalidAmounts", CommerceTest::refundIdConflictAndInvalidAmounts);
        run("fullRefundThenNoFurtherRefund", CommerceTest::fullRefundThenNoFurtherRefund);
        run("multiSkuOrderIsAllOrNothing", CommerceTest::multiSkuOrderIsAllOrNothing);
        run("parallelDuplicateCallbacksCommitOnce", CommerceTest::parallelDuplicateCallbacksCommitOnce);
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

    private static void expirationRacingPaymentCommitsExactlyOnce() throws Exception {
        for (int attempt = 0; attempt < 200; attempt++) {
            ManualTime clock = new ManualTime(20_000);
            CommerceSystem app = app(clock);
            app.addStock("A", 1);
            app.createOrder("O-RACE", List.of(new OrderItem("A", 1, 900)), null, 50);
            clock.set(20_050); // due: a concurrent expiry and payment may both target this order

            CountDownLatch start = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            Future<CallbackResult> payment = pool.submit(() -> {
                start.await();
                return app.paymentSucceeded("P-RACE", "O-RACE", 900);
            });
            Future<Integer> expiry = pool.submit(() -> {
                start.await();
                return app.expireDueOrders();
            });
            start.countDown();
            CallbackResult paid = payment.get(5, TimeUnit.SECONDS);
            int expired = expiry.get(5, TimeUnit.SECONDS);
            pool.shutdown();
            check(pool.awaitTermination(5, TimeUnit.SECONDS), "pool terminated");

            // Exactly one transition wins: either a clean sale or a clean cancellation.
            boolean cleanSale = paid == CallbackResult.ACCEPTED && expired == 0
                    && app.order("O-RACE").status() == OrderStatus.PAID;
            boolean cleanCancel = paid == CallbackResult.REFUND_REQUIRED && expired == 1
                    && app.order("O-RACE").status() == OrderStatus.CANCELLED;
            check(cleanSale || cleanCancel, "race produced a clean, consistent outcome");

            eq(1, app.soldStock("A") + app.availableStock("A"), "no stock duplicated or lost");
            eq(0, app.reservedStock("A"), "no reservation leaked after race");
            eq(1L, countEvents(app, cleanSale ? "PAYMENT_SUCCEEDED" : "PAYMENT_REFUND_REQUIRED"),
                    "exactly one terminal payment event");
        }
    }

    private static void paymentIdConflictDoesNotMutateOrders() {
        CommerceSystem app = app(new ManualTime(30_000));
        app.addStock("A", 2);
        app.createOrder("O-C1", List.of(new OrderItem("A", 1, 500)), null, 5_000);
        app.createOrder("O-C2", List.of(new OrderItem("A", 1, 500)), null, 5_000);
        eq(CallbackResult.ACCEPTED, app.paymentSucceeded("P-C", "O-C1", 500), "first use of id");
        eq(CallbackResult.CONFLICT, app.paymentSucceeded("P-C", "O-C2", 500), "same id, other order");
        eq(CallbackResult.CONFLICT, app.paymentSucceeded("P-C", "O-C1", 400), "same id, other amount");
        eq(OrderStatus.PENDING_PAYMENT, app.order("O-C2").status(), "conflict left order untouched");
        eq(1, app.soldStock("A"), "no extra stock committed");
    }

    private static void wrongAmountCanBeRetriedWithSamePaymentId() {
        CommerceSystem app = app(new ManualTime(40_000));
        app.addStock("A", 1);
        app.createOrder("O-W", List.of(new OrderItem("A", 1, 700)), null, 5_000);
        eq(CallbackResult.REJECTED, app.paymentSucceeded("P-W", "O-W", 699), "wrong amount rejected");
        eq(1, app.reservedStock("A"), "wrong amount left reservation intact");
        eq(CallbackResult.ACCEPTED, app.paymentSucceeded("P-W", "O-W", 700), "retry with valid amount");
        eq(OrderStatus.PAID, app.order("O-W").status(), "order paid after retry");
    }

    private static void secondDistinctPaymentForPaidOrderRequiresRefund() {
        CommerceSystem app = app(new ManualTime(50_000));
        app.addStock("A", 1);
        app.createOrder("O-S", List.of(new OrderItem("A", 1, 800)), null, 5_000);
        eq(CallbackResult.ACCEPTED, app.paymentSucceeded("P-S1", "O-S", 800), "first capture");
        eq(CallbackResult.REFUND_REQUIRED, app.paymentSucceeded("P-S2", "O-S", 800), "second capture compensated");
        eq(1, app.soldStock("A"), "stock committed exactly once");
        eq(1L, countEvents(app, "PAYMENT_SUCCEEDED"), "one success event");
        eq(1L, countEvents(app, "PAYMENT_REFUND_REQUIRED"), "one compensation event");
    }

    private static void refundIdConflictAndInvalidAmounts() {
        CommerceSystem app = app(new ManualTime(60_000));
        app.addStock("A", 2);
        app.createOrder("O-RC1", List.of(new OrderItem("A", 1, 600)), null, 5_000);
        app.createOrder("O-RC2", List.of(new OrderItem("A", 1, 600)), null, 5_000);
        app.paymentSucceeded("P-RC1", "O-RC1", 600);
        app.paymentSucceeded("P-RC2", "O-RC2", 600);
        check(app.refund("R-C", "O-RC1", 200).accepted(), "first refund");
        check(!app.refund("R-C", "O-RC2", 200).accepted(), "refund id conflict across orders");
        check(!app.refund("R-C", "O-RC1", 300).accepted(), "refund id conflict on amount");
        check(!app.refund("R-NEG", "O-RC1", -1).accepted(), "negative refund rejected");
        check(!app.refund("R-ZERO", "O-RC1", 0).accepted(), "zero refund rejected");
        check(!app.refund("R-BIG", "O-RC1", 500).accepted(), "exceeds remaining refundable");
        eq(200L, app.order("O-RC1").refundedCents(), "only the first refund applied");
    }

    private static void fullRefundThenNoFurtherRefund() {
        CommerceSystem app = app(new ManualTime(70_000));
        app.addStock("A", 1);
        app.addCoupon("CP", 100);
        app.createOrder("O-FR", List.of(new OrderItem("A", 1, 1_000)), "CP", 5_000);
        app.paymentSucceeded("P-FR", "O-FR", 900);
        RefundResult full = app.refund("R-FR", "O-FR", 900);
        check(full.accepted(), "full refund accepted");
        eq(OrderStatus.REFUNDED, app.order("O-FR").status(), "order fully refunded");
        eq(CouponStatus.USED, app.couponStatus("CP"), "used coupon not revived by refund");
        eq(1, app.soldStock("A"), "refund did not change inventory");
        check(!app.refund("R-FR2", "O-FR", 1).accepted(), "no refund after full refund");
    }

    private static void multiSkuOrderIsAllOrNothing() {
        CommerceSystem app = app(new ManualTime(80_000));
        app.addStock("A", 3);
        app.addCoupon("CP2", 50);
        expect(IllegalStateException.class, () -> app.createOrder("O-MS",
                List.of(new OrderItem("A", 2, 100), new OrderItem("B", 1, 100)), "CP2", 5_000),
                "second sku missing stock");
        eq(3, app.availableStock("A"), "first sku not reserved");
        eq(0, app.reservedStock("A"), "no reservation leaked");
        eq(CouponStatus.AVAILABLE, app.couponStatus("CP2"), "coupon not stranded");
    }

    private static void parallelDuplicateCallbacksCommitOnce() throws Exception {
        ManualTime clock = new ManualTime(90_000);
        CommerceSystem app = app(clock);
        app.addStock("A", 1);
        app.createOrder("O-P", List.of(new OrderItem("A", 1, 1_000)), null, 5_000);
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<CallbackResult>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            results.add(pool.submit(() -> {
                start.await();
                return app.paymentSucceeded("P-P", "O-P", 1_000);
            }));
        }
        start.countDown();
        int accepted = 0;
        int duplicate = 0;
        for (Future<CallbackResult> future : results) {
            CallbackResult result = future.get(5, TimeUnit.SECONDS);
            if (result == CallbackResult.ACCEPTED) accepted++;
            if (result == CallbackResult.DUPLICATE) duplicate++;
        }
        pool.shutdown();
        check(pool.awaitTermination(5, TimeUnit.SECONDS), "pool terminated");
        eq(1, accepted, "exactly one callback accepted");
        eq(threads - 1, duplicate, "all others are duplicates");
        eq(1, app.soldStock("A"), "stock committed exactly once");
        eq(0, app.reservedStock("A"), "no reservation leaked");
        eq(1L, countEvents(app, "PAYMENT_SUCCEEDED"), "one success event");
    }

    private static CommerceSystem app(ManualTime clock) {
        return new CommerceSystem(clock);
    }

    private static long countEvents(CommerceSystem app, String type) {
        return app.events().stream().filter(event -> event.type().equals(type)).count();
    }

    @FunctionalInterface
    private interface TestBody {
        void run() throws Exception;
    }

    private static void run(String name, TestBody test) {
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
