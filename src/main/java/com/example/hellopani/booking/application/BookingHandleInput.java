package com.example.hellopani.booking.application;

import com.example.hellopani.payment.domain.PaymentMethodType;

import java.util.List;

public record BookingHandleInput(
        String checkoutId,
        String userId,
        List<ComponentInput> components
) {

    public record ComponentInput(PaymentMethodType type, long amount) {
    }
}
