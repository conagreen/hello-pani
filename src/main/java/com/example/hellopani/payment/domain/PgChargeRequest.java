package com.example.hellopani.payment.domain;

public record PgChargeRequest(
        String pgIdempotencyKey,
        String userId,
        long amount,
        PgPaymentInstrument instrument
) {
}
