package bench.commerce;

import java.util.List;

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

        run("repeatedCancellationDoesNotMoveStockTwice", CommerceTest::repeatedCancellationDoesNotMoveStockTwice);
        run("expiryIsIdempotentAndReleasesExactlyOnce", CommerceTest::expiryIsIdempotentAndReleasesExactlyOnce);
        run("paidOrderCannotBeCancelledOrExpired", CommerceTest::paidOrderCannotBeCancelledOrExpired);
        run("latePaymentAfterExpiryNeedsRefundWithoutStockChange", CommerceTest::latePaymentAfterExpiryNeedsRefundWithoutStockChange);
        run("duplicateCallbackOnCancelledOrderIsIdempotent", CommerceTest::duplicateCallbackOnCancelledOrderIsIdempotent);
        run("secondDistinctPaymentNeedsRefund", CommerceTest::secondDistinctPaymentNeedsRefund);
        run("paymentIdReuseConflictLeavesBothOrdersUntouched", CommerceTest::paymentIdReuseConflictLeavesBothOrdersUntouched);
        run("amountMismatchLeavesNoTraceAndAllowsRetry", CommerceTest::amountMismatchLeavesNoTraceAndAllowsRetry);
        run("rejectedCallbackCreatesNoPaymentRecord", CommerceTest::rejectedCallbackCreatesNoPaymentRecord);
        run("multiSkuReservationIsAllOrNothing", CommerceTest::multiSkuReservationIsAllOrNothing);
        run("couponCannotBeReservedByTwoLiveOrders", CommerceTest::couponCannotBeReservedByTwoLiveOrders);
        run("fullRefundMovesToRefundedAndKeepsCouponUsed", CommerceTest::fullRefundMovesToRefundedAndKeepsCouponUsed);
        run("refundIdReuseConflictDoesNotMoveMoney", CommerceTest::refundIdReuseConflictDoesNotMoveMoney);
        run("refundsRejectInvalidRequestsAndUnpaidOrders", CommerceTest::refundsRejectInvalidRequestsAndUnpaidOrders);
        run("inventoryBucketsStayBalanced", CommerceTest::inventoryBucketsStayBalanced);
        run("concurrentCancelAndTimeoutReleaseOnce", CommerceTest::concurrentCancelAndTimeoutReleaseOnce);
        run("concurrentPaymentAndExpiryCommitOrReleaseOnce", CommerceTest::concurrentPaymentAndExpiryCommitOrReleaseOnce);
        run("concurrentDuplicateCallbacksCommitOnce", CommerceTest::concurrentDuplicateCallbacksCommitOnce);
        run("concurrentDuplicateRefundsApplyOnce", CommerceTest::concurrentDuplicateRefundsApplyOnce);

        System.out.println("RESULT: " + passed + " passed, " + failed + " failed");
        if (failed != 0) {
            throw new AssertionError("visible commerce tests failed: " + failed);
        }
    }

    // --- original visible contract -------------------------------------------------------------

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
        eq(1, app.expireDueOrders(), "exactly one due order expires at the boundary");
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

    // --- repeated transitions ------------------------------------------------------------------

    private static void repeatedCancellationDoesNotMoveStockTwice() {
        CommerceSystem app = app(new ManualTime(20_000));
        app.addStock("A", 2);
        app.addCoupon("C", 100);
        app.createOrder("O", List.of(new OrderItem("A", 2, 100)), "C", 1_000);
        check(app.cancelOrder("O"), "first cancel succeeds");
        check(!app.cancelOrder("O"), "repeated cancel is a no-op");
        check(!app.cancelOrder("O"), "third cancel is a no-op");
        eq(2, app.availableStock("A"), "stock released exactly once");
        eq(0, app.reservedStock("A"), "no reserved stock left");
        eq(0, app.soldStock("A"), "nothing sold");
        eq(1L, countEvents(app, "ORDER_CANCELLED"), "one cancellation event");
    }

    private static void expiryIsIdempotentAndReleasesExactlyOnce() {
        ManualTime clock = new ManualTime(21_000);
        CommerceSystem app = app(clock);
        app.addStock("A", 2);
        app.addCoupon("C", 50);
        app.createOrder("O", List.of(new OrderItem("A", 2, 100)), "C", 100);
        clock.set(21_100);
        eq(1, app.expireDueOrders(), "order expires at boundary");
        eq(0, app.expireDueOrders(), "second scan has nothing left to expire");
        check(!app.cancelOrder("O"), "manual cancel after expiry is a no-op");
        eq(2, app.availableStock("A"), "expiry restored stock once");
        eq(0, app.reservedStock("A"), "reservation cleared");
        eq(CouponStatus.AVAILABLE, app.couponStatus("C"), "coupon released once");
        eq(1L, countEvents(app, "ORDER_CANCELLED"), "one cancellation event");
    }

    private static void paidOrderCannotBeCancelledOrExpired() {
        ManualTime clock = new ManualTime(22_000);
        CommerceSystem app = app(clock);
        app.addStock("A", 2);
        app.createOrder("O", List.of(new OrderItem("A", 2, 100)), null, 100);
        eq(CallbackResult.ACCEPTED, app.paymentSucceeded("P", "O", 200), "payment accepted");
        clock.set(22_500);
        check(!app.cancelOrder("O"), "paid order cannot be cancelled");
        eq(0, app.expireDueOrders(), "paid order is not expired");
        eq(OrderStatus.PAID, app.order("O").status(), "stays paid");
        eq(0, app.availableStock("A"), "stock stays sold");
        eq(2, app.soldStock("A"), "sold exactly once");
    }

    // --- payment callbacks ---------------------------------------------------------------------

    private static void latePaymentAfterExpiryNeedsRefundWithoutStockChange() {
        ManualTime clock = new ManualTime(23_000);
        CommerceSystem app = app(clock);
        app.addStock("A", 3);
        app.createOrder("O", List.of(new OrderItem("A", 3, 100)), null, 100);
        clock.set(23_100);
        eq(1, app.expireDueOrders(), "order expired");
        eq(CallbackResult.REFUND_REQUIRED, app.paymentSucceeded("P", "O", 300), "late capture needs refund");
        for (int i = 0; i < 3; i++) {
            eq(CallbackResult.REFUND_REQUIRED, app.paymentSucceeded("P", "O", 300), "replay needs refund");
        }
        eq(OrderStatus.CANCELLED, app.order("O").status(), "still cancelled");
        eq(3, app.availableStock("A"), "stock not reacquired");
        eq(0, app.reservedStock("A"), "no reservation");
        eq(0, app.soldStock("A"), "no commit");
        eq(1L, countEvents(app, "PAYMENT_REFUND_REQUIRED"), "single compensation event");
        eq(0L, countEvents(app, "PAYMENT_SUCCEEDED"), "no success event");
    }

    private static void duplicateCallbackOnCancelledOrderIsIdempotent() {
        CommerceSystem app = app(new ManualTime(24_000));
        app.addStock("A", 1);
        app.createOrder("O", List.of(new OrderItem("A", 1, 400)), null, 5_000);
        check(app.cancelOrder("O"), "cancel");
        eq(CallbackResult.REFUND_REQUIRED, app.paymentSucceeded("P", "O", 400), "first late capture");
        eq(CallbackResult.REFUND_REQUIRED, app.paymentSucceeded("P", "O", 400), "replayed late capture");
        eq(CallbackResult.CONFLICT, app.paymentSucceeded("P", "OTHER", 400), "same id, different order");
        eq(1L, countEvents(app, "PAYMENT_REFUND_REQUIRED"), "compensation recorded once");
        eq(1, app.availableStock("A"), "stock unchanged");
    }

    private static void secondDistinctPaymentNeedsRefund() {
        CommerceSystem app = app(new ManualTime(25_000));
        app.addStock("A", 2);
        app.createOrder("O", List.of(new OrderItem("A", 1, 500)), null, 5_000);
        eq(CallbackResult.ACCEPTED, app.paymentSucceeded("P-1", "O", 500), "first payment");
        eq(CallbackResult.REFUND_REQUIRED, app.paymentSucceeded("P-2", "O", 500), "second capture needs refund");
        eq(CallbackResult.REFUND_REQUIRED, app.paymentSucceeded("P-2", "O", 500), "replay of second capture");
        eq(OrderStatus.PAID, app.order("O").status(), "order still paid once");
        eq(1, app.soldStock("A"), "inventory committed once");
        eq(0, app.reservedStock("A"), "no reservation left");
        eq(1, app.availableStock("A"), "one unit still available");
        eq(1L, countEvents(app, "PAYMENT_SUCCEEDED"), "one success event");
        eq(1L, countEvents(app, "PAYMENT_REFUND_REQUIRED"), "one compensation event");
        eq(500L, app.order("O").paidCents(), "captured amount unchanged");
    }

    private static void paymentIdReuseConflictLeavesBothOrdersUntouched() {
        CommerceSystem app = app(new ManualTime(26_000));
        app.addStock("A", 4);
        app.createOrder("O-1", List.of(new OrderItem("A", 1, 100)), null, 5_000);
        app.createOrder("O-2", List.of(new OrderItem("A", 1, 200)), null, 5_000);
        eq(CallbackResult.ACCEPTED, app.paymentSucceeded("P", "O-1", 100), "first payment");
        eq(CallbackResult.CONFLICT, app.paymentSucceeded("P", "O-2", 200), "reused id for another order");
        eq(CallbackResult.CONFLICT, app.paymentSucceeded("P", "O-1", 200), "same id, different amount");
        eq(OrderStatus.PENDING_PAYMENT, app.order("O-2").status(), "second order untouched");
        eq(1, app.reservedStock("A"), "second reservation untouched");
        eq(2, app.availableStock("A"), "first order left available stock intact");
    }

    private static void amountMismatchLeavesNoTraceAndAllowsRetry() {
        CommerceSystem app = app(new ManualTime(27_000));
        app.addStock("A", 3);
        app.addCoupon("C", 100);
        app.createOrder("O", List.of(new OrderItem("A", 1, 1_000)), "C", 5_000);
        eq(CallbackResult.REJECTED, app.paymentSucceeded("P", "O", 800), "wrong amount rejected");
        eq(OrderStatus.PENDING_PAYMENT, app.order("O").status(), "order untouched");
        eq(1, app.reservedStock("A"), "reservation untouched");
        eq(CouponStatus.RESERVED, app.couponStatus("C"), "coupon untouched");
        eq(0L, countEvents(app, "PAYMENT_SUCCEEDED"), "no success event");
        eq(CallbackResult.ACCEPTED, app.paymentSucceeded("P", "O", 900), "same id retried with valid payload");
        eq(OrderStatus.PAID, app.order("O").status(), "order paid");
        eq(CouponStatus.USED, app.couponStatus("C"), "coupon consumed on real payment");
    }

    private static void rejectedCallbackCreatesNoPaymentRecord() {
        CommerceSystem app = app(new ManualTime(28_000));
        app.addStock("A", 2);
        app.createOrder("O", List.of(new OrderItem("A", 1, 300)), null, 5_000);
        eq(CallbackResult.REJECTED, app.paymentSucceeded("P", "UNKNOWN", 300), "unknown order rejected");
        eq(CallbackResult.REJECTED, app.paymentSucceeded(" ", "O", 300), "blank payment id rejected");
        eq(CallbackResult.REJECTED, app.paymentSucceeded("P", "O", 0), "non-positive amount rejected");
        eq(1, app.reservedStock("A"), "no stock movement from rejected callbacks");
        eq(CallbackResult.ACCEPTED, app.paymentSucceeded("P", "O", 300), "rejected id still usable");
    }

    // --- inventory and coupons -----------------------------------------------------------------

    private static void multiSkuReservationIsAllOrNothing() {
        CommerceSystem app = app(new ManualTime(29_000));
        app.addStock("A", 5);
        app.addStock("B", 1);
        expect(IllegalStateException.class, () -> app.createOrder("O",
                List.of(new OrderItem("A", 3, 100), new OrderItem("B", 2, 100)), null, 5_000),
                "second line exceeds stock");
        eq(5, app.availableStock("A"), "A not partially reserved");
        eq(0, app.reservedStock("A"), "A reservation rolled back");
        eq(1, app.availableStock("B"), "B untouched");
        check(app.order("O") == null, "no order record created");
        eq(0L, countEvents(app, "ORDER_CREATED"), "no order event");
    }

    private static void couponCannotBeReservedByTwoLiveOrders() {
        ManualTime clock = new ManualTime(30_000);
        CommerceSystem app = app(clock);
        app.addStock("A", 5);
        app.addCoupon("C", 100);
        app.createOrder("O-1", List.of(new OrderItem("A", 1, 500)), "C", 5_000);
        expect(IllegalStateException.class, () -> app.createOrder("O-2",
                List.of(new OrderItem("A", 1, 500)), "C", 5_000), "coupon already reserved");
        eq(4, app.availableStock("A"), "failed order left the live order's reservation alone");
        eq(CouponStatus.RESERVED, app.couponStatus("C"), "coupon still held by the live order");
        check(app.cancelOrder("O-1"), "cancel first order");
        eq(CouponStatus.AVAILABLE, app.couponStatus("C"), "coupon reusable after cancel");
        app.createOrder("O-3", List.of(new OrderItem("A", 1, 500)), "C", 5_000);
        eq(400L, app.order("O-3").payableCents(), "discount applied");
    }

    // --- refunds -------------------------------------------------------------------------------

    private static void fullRefundMovesToRefundedAndKeepsCouponUsed() {
        CommerceSystem app = app(new ManualTime(31_000));
        app.addStock("A", 1);
        app.addCoupon("C", 100);
        app.createOrder("O", List.of(new OrderItem("A", 1, 1_000)), "C", 5_000);
        eq(CallbackResult.ACCEPTED, app.paymentSucceeded("P", "O", 900), "payment");
        check(app.refund("R-1", "O", 400).accepted(), "partial refund");
        eq(OrderStatus.PARTIALLY_REFUNDED, app.order("O").status(), "partially refunded");
        check(app.refund("R-2", "O", 500).accepted(), "remaining refund");
        eq(OrderStatus.REFUNDED, app.order("O").status(), "fully refunded");
        eq(900L, app.order("O").refundedCents(), "total refunded equals capture");
        eq(CouponStatus.USED, app.couponStatus("C"), "refund does not free the coupon");
        check(!app.refund("R-3", "O", 1).accepted(), "nothing left to refund");
    }

    private static void refundIdReuseConflictDoesNotMoveMoney() {
        CommerceSystem app = app(new ManualTime(32_000));
        app.addStock("A", 2);
        app.createOrder("O-1", List.of(new OrderItem("A", 1, 1_000)), null, 5_000);
        app.createOrder("O-2", List.of(new OrderItem("A", 1, 800)), null, 5_000);
        app.paymentSucceeded("P-1", "O-1", 1_000);
        app.paymentSucceeded("P-2", "O-2", 800);
        check(app.refund("R", "O-1", 300).accepted(), "first refund");
        RefundResult otherAmount = app.refund("R", "O-1", 400);
        check(!otherAmount.accepted() && !otherAmount.duplicate(), "same id, different amount");
        RefundResult otherOrder = app.refund("R", "O-2", 300);
        check(!otherOrder.accepted(), "same id, different order");
        eq(300L, app.order("O-1").refundedCents(), "refund total unchanged");
        eq(0L, app.order("O-2").refundedCents(), "other order untouched");
        eq(1L, countEvents(app, "REFUND_SUCCEEDED"), "one refund event");
    }

    private static void refundsRejectInvalidRequestsAndUnpaidOrders() {
        CommerceSystem app = app(new ManualTime(33_000));
        app.addStock("A", 2);
        app.createOrder("O", List.of(new OrderItem("A", 1, 1_000)), null, 5_000);
        check(!app.refund("R", "O", 100).accepted(), "unpaid order cannot be refunded");
        app.paymentSucceeded("P", "O", 1_000);
        check(!app.refund("R", "O", 0).accepted(), "zero amount rejected");
        check(!app.refund("R", "O", -5).accepted(), "negative amount rejected");
        check(!app.refund(" ", "O", 10).accepted(), "blank refund id rejected");
        check(!app.refund("R", "MISSING", 10).accepted(), "unknown order rejected");
        eq(0L, app.order("O").refundedCents(), "no money moved");
        eq(1, app.soldStock("A"), "refunds do not touch inventory");
    }

    // --- invariants and concurrency ---------------------------------------------------------------

    private static void inventoryBucketsStayBalanced() {
        ManualTime clock = new ManualTime(34_000);
        CommerceSystem app = app(clock);
        app.addStock("A", 10);
        app.addStock("B", 10);
        app.createOrder("O-1", List.of(new OrderItem("A", 4, 100)), null, 1_000);
        app.createOrder("O-2", List.of(new OrderItem("B", 3, 100)), null, 1_000);
        app.paymentSucceeded("P", "O-2", 300);
        app.cancelOrder("O-1");
        clock.set(35_000);
        app.expireDueOrders();
        assertBuckets(app, "A", 10);
        assertBuckets(app, "B", 10);
    }

    private static void concurrentCancelAndTimeoutReleaseOnce() throws Throwable {
        for (int round = 0; round < 50; round++) {
            ManualTime clock = new ManualTime(40_000);
            CommerceSystem app = app(clock);
            app.addStock("A", 2);
            app.addCoupon("C", 50);
            String orderId = "O-" + round;
            app.createOrder(orderId, List.of(new OrderItem("A", 2, 100)), "C", 100);
            clock.set(40_100);
            Race race = new Race();
            race.start(() -> app.cancelOrder(orderId), () -> app.expireDueOrders());
            eq(2, app.availableStock("A"), "released once under cancel/expiry race");
            eq(0, app.reservedStock("A"), "no residual reservation");
            eq(0, app.soldStock("A"), "never sold");
            eq(CouponStatus.AVAILABLE, app.couponStatus("C"), "coupon released once");
            eq(1L, countEvents(app, "ORDER_CANCELLED"), "single cancellation event");
        }
    }

    private static void concurrentPaymentAndExpiryCommitOrReleaseOnce() throws Throwable {
        for (int round = 0; round < 50; round++) {
            ManualTime clock = new ManualTime(50_000);
            CommerceSystem app = app(clock);
            app.addStock("A", 2);
            String orderId = "O-" + round;
            app.createOrder(orderId, List.of(new OrderItem("A", 2, 100)), null, 100);
            clock.set(50_100);
            Race race = new Race();
            String paymentId = "P-" + round;
            race.start(() -> app.paymentSucceeded(paymentId, orderId, 200),
                    () -> app.expireDueOrders());
            OrderView order = app.order(orderId);
            if (order.status() == OrderStatus.PAID) {
                eq(0, app.reservedStock("A"), "paid order holds no reservation");
                eq(2, app.soldStock("A"), "paid order committed stock");
                eq(0, app.availableStock("A"), "paid order did not release stock");
                eq(1L, countEvents(app, "PAYMENT_SUCCEEDED"), "single success event");
            } else {
                eq(OrderStatus.CANCELLED, order.status(), "otherwise the order expired");
                eq(2, app.availableStock("A"), "expired order released stock");
                eq(0, app.soldStock("A"), "expired order never commits stock");
                eq(0, app.reservedStock("A"), "no reservation left");
                eq(1L, countEvents(app, "PAYMENT_REFUND_REQUIRED"), "late capture flagged for refund");
            }
            assertBuckets(app, "A", 2);
        }
    }

    private static void concurrentDuplicateCallbacksCommitOnce() throws Throwable {
        for (int round = 0; round < 50; round++) {
            CommerceSystem app = app(new ManualTime(60_000));
            app.addStock("A", 1);
            String orderId = "O-" + round;
            app.createOrder(orderId, List.of(new OrderItem("A", 1, 700)), null, 10_000);
            Race race = new Race(3);
            race.start(() -> app.paymentSucceeded("P", orderId, 700),
                    () -> app.paymentSucceeded("P", orderId, 700),
                    () -> app.paymentSucceeded("P", orderId, 700));
            eq(OrderStatus.PAID, app.order(orderId).status(), "paid once");
            eq(1, app.soldStock("A"), "sold once");
            eq(0, app.reservedStock("A"), "reservation consumed");
            eq(1L, countEvents(app, "PAYMENT_SUCCEEDED"), "single success event");
            eq(0L, countEvents(app, "PAYMENT_REFUND_REQUIRED"), "replays are not compensations");
        }
    }

    private static void concurrentDuplicateRefundsApplyOnce() throws Throwable {
        for (int round = 0; round < 50; round++) {
            CommerceSystem app = app(new ManualTime(70_000));
            app.addStock("A", 1);
            String orderId = "O-" + round;
            app.createOrder(orderId, List.of(new OrderItem("A", 1, 1_000)), null, 10_000);
            app.paymentSucceeded("P", orderId, 1_000);
            Race race = new Race(3);
            race.start(() -> app.refund("R", orderId, 400),
                    () -> app.refund("R", orderId, 400),
                    () -> app.refund("R", orderId, 400));
            eq(400L, app.order(orderId).refundedCents(), "refund applied once");
            eq(OrderStatus.PARTIALLY_REFUNDED, app.order(orderId).status(), "partial refund state");
            eq(1L, countEvents(app, "REFUND_SUCCEEDED"), "single refund event");
        }
    }

    // --- helpers -------------------------------------------------------------------------------

    private static CommerceSystem app(ManualTime clock) {
        return new CommerceSystem(clock);
    }

    private static void assertBuckets(CommerceSystem app, String sku, int total) {
        int available = app.availableStock(sku);
        int reserved = app.reservedStock(sku);
        int sold = app.soldStock(sku);
        check(available >= 0 && reserved >= 0 && sold >= 0,
                sku + " buckets must never be negative: " + available + "/" + reserved + "/" + sold);
        eq(total, available + reserved + sold, sku + " buckets must sum to the stock total");
    }

    private static long countEvents(CommerceSystem app, String type) {
        return app.events().stream().filter(event -> event.type().equals(type)).count();
    }

    /** Starts threads on a shared latch so a race actually overlaps instead of running in sequence. */
    private static final class Race {
        private final java.util.concurrent.CyclicBarrier gate;

        Race() {
            this(2);
        }

        Race(int parties) {
            this.gate = new java.util.concurrent.CyclicBarrier(parties);
        }

        void start(Runnable... actions) {
            Thread[] threads = new Thread[actions.length];
            for (int i = 0; i < actions.length; i++) {
                Runnable action = actions[i];
                threads[i] = new Thread(() -> {
                    awaitGate();
                    action.run();
                });
                threads[i].start();
            }
            for (Thread thread : threads) {
                join(thread);
            }
        }

        private void awaitGate() {
            try {
                gate.await();
            } catch (Exception error) {
                throw new IllegalStateException("race gate failed", error);
            }
        }

        private static void join(Thread thread) {
            try {
                thread.join();
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("race interrupted", error);
            }
        }
    }

    private static void run(String name, ThrowingTest test) {
        try {
            test.run();
            passed++;
            System.out.println("PASS " + name);
        } catch (Throwable error) {
            failed++;
            System.out.println("FAIL " + name + ": " + error.getMessage());
        }
    }

    @FunctionalInterface
    private interface ThrowingTest {
        void run() throws Throwable;
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
