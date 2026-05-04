package com.example.hellopani.payment.domain;

public sealed interface ChargeOutcome {

    PaymentMethodType type();

    record Succeeded(PaymentMethodType type, String externalTransactionId) implements ChargeOutcome {
    }

    record ConfirmedFailure(PaymentMethodType type, FailureReason reason) implements ChargeOutcome {
    }

    record ResultPending(PaymentMethodType type, String pgIdempotencyKey) implements ChargeOutcome {
    }
}
