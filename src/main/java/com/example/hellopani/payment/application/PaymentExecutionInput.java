package com.example.hellopani.payment.application;

import java.util.List;
import java.util.Map;
import com.example.hellopani.payment.domain.ChargeRequest;
import com.example.hellopani.payment.domain.PaymentMethodType;

public record PaymentExecutionInput(
        long paymentId,
        long totalAmount,
        List<ChargeRequest> requests,
        Map<PaymentMethodType, Long> componentIds
) {
}
