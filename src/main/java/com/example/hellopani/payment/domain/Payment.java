package com.example.hellopani.payment.domain;

import java.time.LocalDateTime;

public record Payment(
        long paymentId,
        String checkoutId,
        long bookingId,
        String userId,
        PaymentStatus status,
        long totalAmount,
        String pgIdempotencyKey,
        LocalDateTime createdAt,
        LocalDateTime completedAt
) {
}
