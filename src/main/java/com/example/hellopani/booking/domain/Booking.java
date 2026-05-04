package com.example.hellopani.booking.domain;

import java.time.LocalDateTime;

public record Booking(
        long bookingId,
        String checkoutId,
        String userId,
        long productId,
        BookingStatus status,
        long totalAmount,
        LocalDateTime createdAt,
        LocalDateTime confirmedAt
) {
}
