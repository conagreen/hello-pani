package com.example.hellopani.booking.application;

public record BookingResultPayload(
        String checkoutId,
        String status,
        Long bookingId,
        Long paymentId,
        String message
) {

    public static final String STATUS_CONFIRMED = "CONFIRMED";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_PENDING = "PENDING";
}
