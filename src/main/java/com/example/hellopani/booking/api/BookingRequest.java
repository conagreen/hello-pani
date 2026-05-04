package com.example.hellopani.booking.api;

import com.example.hellopani.payment.domain.PaymentMethodType;

import java.util.List;

public record BookingRequest(
        String checkoutId,
        List<PaymentInput> payments
) {

    public record PaymentInput(PaymentMethodType method, long amount) {
    }
}
