package bench.commerce;

final class Coupon {
    final String code;
    final long discountCents;
    CouponStatus status = CouponStatus.AVAILABLE;
    String orderId;

    Coupon(String code, long discountCents) {
        this.code = code;
        this.discountCents = discountCents;
    }
}
