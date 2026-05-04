package com.example.hellopani.payment.application;

import java.util.List;
import com.example.hellopani.payment.domain.ChargeOutcome;
import com.example.hellopani.payment.domain.FailureReason;
import com.example.hellopani.payment.domain.PaymentMethodType;

public sealed interface CompositionResult {

    List<ChargeOutcome.Succeeded> successfulComponents();

    record AllSucceeded(List<ChargeOutcome.Succeeded> successfulComponents) implements CompositionResult {
    }

    record ConfirmedFailure(
            FailureReason reason,
            PaymentMethodType failedAt,
            List<ChargeOutcome.Succeeded> successfulComponents
    ) implements CompositionResult {
    }

    record ResultPending(
            PaymentMethodType pendingAt,
            String pgIdempotencyKey,
            List<ChargeOutcome.Succeeded> successfulComponents
    ) implements CompositionResult {
    }
}
