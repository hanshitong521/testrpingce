package bench.commerce;

final class CouponService {
    private final CommerceStore store;

    CouponService(CommerceStore store) {
        this.store = store;
    }

    void addCoupon(String code, long discountCents) {
        if (code == null || code.isBlank() || discountCents <= 0) {
            throw new IllegalArgumentException("invalid coupon");
        }
        if (store.coupons.putIfAbsent(code, new Coupon(code, discountCents)) != null) {
            throw new IllegalArgumentException("coupon already exists");
        }
    }

    long reserve(String code, String orderId, long subtotal) {
        if (code == null || code.isBlank()) {
            return 0;
        }
        Coupon coupon = store.coupons.get(code);
        if (coupon == null || coupon.status != CouponStatus.AVAILABLE) {
            throw new IllegalStateException("coupon is not available");
        }
        coupon.status = CouponStatus.RESERVED;
        coupon.orderId = orderId;
        return Math.min(coupon.discountCents, subtotal);
    }

    void release(String code, String orderId) {
        if (code == null) {
            return;
        }
        Coupon coupon = store.coupons.get(code);
        if (coupon != null && coupon.status == CouponStatus.RESERVED && orderId.equals(coupon.orderId)) {
            coupon.status = CouponStatus.AVAILABLE;
            coupon.orderId = null;
        }
    }

    void consume(String code, String orderId) {
        if (code == null) {
            return;
        }
        Coupon coupon = store.coupons.get(code);
        if (coupon != null && coupon.status == CouponStatus.RESERVED && orderId.equals(coupon.orderId)) {
            coupon.status = CouponStatus.USED;
        }
    }

    CouponStatus status(String code) {
        Coupon coupon = store.coupons.get(code);
        return coupon == null ? null : coupon.status;
    }
}
