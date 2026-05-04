package com.example.hellopani.payment.domain;

public interface PaymentMethod {

    PaymentMethodType type();

    ChargeOutcome charge(ChargeRequest request);

    void refund(ChargeRequest request, ChargeOutcome.Succeeded prior);
}
