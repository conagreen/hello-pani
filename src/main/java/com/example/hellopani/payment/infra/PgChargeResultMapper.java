package com.example.hellopani.payment.infra;

import com.example.hellopani.payment.domain.ChargeOutcome;
import com.example.hellopani.payment.domain.PaymentMethodType;
import com.example.hellopani.payment.domain.PgChargeResult;

final class PgChargeResultMapper {

    private PgChargeResultMapper() {
    }

    static ChargeOutcome toOutcome(PaymentMethodType type, PgChargeResult result) {
        return switch (result) {
            case PgChargeResult.Approved a -> new ChargeOutcome.Succeeded(type, a.externalTransactionId());
            case PgChargeResult.Declined d -> new ChargeOutcome.ConfirmedFailure(type, d.reason());
            case PgChargeResult.Pending p -> new ChargeOutcome.ResultPending(type, p.pgIdempotencyKey());
            case PgChargeResult.NotFound n -> throw new IllegalStateException(
                    "PG returned NotFound on charge for type " + type);
        };
    }
}
