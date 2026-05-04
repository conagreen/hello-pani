package com.example.hellopani.payment.application;

import java.util.List;
import com.example.hellopani.payment.domain.PaymentMethodType;

public record PaymentExecutionContext(
        String checkoutId,
        long bookingId,
        String userId,
        long totalAmount,
        List<ComponentRequest> components
) {

    public record ComponentRequest(PaymentMethodType type, long amount) {
    }
}
