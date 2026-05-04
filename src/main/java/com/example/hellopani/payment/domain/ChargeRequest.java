package com.example.hellopani.payment.domain;

public record ChargeRequest(
        String checkoutId,
        String userId,
        PaymentMethodType type,
        long amount
) {
}
