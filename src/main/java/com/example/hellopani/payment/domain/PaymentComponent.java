package com.example.hellopani.payment.domain;

public record PaymentComponent(
        long paymentComponentId,
        long paymentId,
        PaymentMethodType method,
        long amount,
        PaymentComponentStatus status,
        String externalTransactionId
) {
}
