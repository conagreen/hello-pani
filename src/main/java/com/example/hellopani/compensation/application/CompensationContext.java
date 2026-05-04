package com.example.hellopani.compensation.application;

public record CompensationContext(
        String checkoutId,
        String userId,
        long productId,
        long pointRefundAmount,
        long paymentId
) {

    public boolean shouldRefundPoint() {
        return pointRefundAmount > 0;
    }
}
