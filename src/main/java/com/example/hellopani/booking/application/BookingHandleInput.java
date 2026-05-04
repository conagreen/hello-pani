package com.example.hellopani.booking.application;

import java.util.List;
import com.example.hellopani.payment.domain.PaymentMethodType;

public record BookingHandleInput(
        String checkoutId,
        String userId,
        List<ComponentInput> components
) {

    public record ComponentInput(PaymentMethodType type, long amount) {
    }
}
