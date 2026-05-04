package com.example.hellopani.booking.api;

import java.util.List;
import com.example.hellopani.payment.domain.PaymentMethodType;

public record BookingRequest(
        String checkoutId,
        List<PaymentInput> payments
) {

    public record PaymentInput(PaymentMethodType method, long amount) {
    }
}
