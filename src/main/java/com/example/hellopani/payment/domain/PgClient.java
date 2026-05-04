package com.example.hellopani.payment.domain;

public interface PgClient {

    PgChargeResult charge(PgChargeRequest request);

    PgChargeResult lookupResult(String pgIdempotencyKey);

    void refund(String pgIdempotencyKey);
}
