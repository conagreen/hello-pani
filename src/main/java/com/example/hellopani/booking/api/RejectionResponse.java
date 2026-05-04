package com.example.hellopani.booking.api;

public record RejectionResponse(
        String code,
        boolean retryable,
        int retryAfterSeconds,
        String message
) {
}
