package com.example.hellopani.payment.domain;

public sealed interface PgChargeResult {

    record Approved(String externalTransactionId) implements PgChargeResult {
    }

    record Declined(FailureReason reason) implements PgChargeResult {
    }

    record Pending(String pgIdempotencyKey) implements PgChargeResult {
    }

    record NotFound() implements PgChargeResult {
    }
}
