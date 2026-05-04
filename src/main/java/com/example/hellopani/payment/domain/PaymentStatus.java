package com.example.hellopani.payment.domain;

public enum PaymentStatus {
    PROCESSING,
    RESULT_PENDING,
    SUCCEEDED,
    FAILED,
    COMPENSATING,
    COMPENSATED,
    REFUND_FAILED
}
