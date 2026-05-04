package com.example.hellopani.payment.application;

import java.util.List;
import com.example.hellopani.payment.domain.ChargeOutcome;
import com.example.hellopani.payment.domain.FailureReason;
import com.example.hellopani.payment.domain.PaymentMethodType;

public sealed interface PaymentExecutionResult {

    long paymentId();

    record Succeeded(long paymentId, List<ChargeOutcome.Succeeded> componentResults) implements PaymentExecutionResult {
    }

    record Failed(
            long paymentId,
            FailureReason reason,
            PaymentMethodType failedAt
    ) implements PaymentExecutionResult {
    }

    record Pending(
            long paymentId,
            PaymentMethodType pendingAt,
            String pgIdempotencyKey
    ) implements PaymentExecutionResult {
    }
}
