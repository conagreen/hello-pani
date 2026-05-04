package com.example.hellopani.booking.application;

public sealed interface BookingExecutionResult {

    record Confirmed(String checkoutId, long bookingId, long paymentId) implements BookingExecutionResult {
    }

    record Failed(String checkoutId, long bookingId, long paymentId, String reason) implements BookingExecutionResult {
    }

    record Pending(String checkoutId, long bookingId, long paymentId, String pgIdempotencyKey) implements BookingExecutionResult {
    }

    record Rejected(
            String checkoutId,
            RejectionCode code,
            boolean retryable,
            int retryAfterSeconds,
            String message
    ) implements BookingExecutionResult {
    }

    record Replayed(String checkoutId, String cachedJson) implements BookingExecutionResult {
    }

    enum RejectionCode {
        CHECKOUT_NOT_FOUND,
        CHECKOUT_USER_MISMATCH,
        CHECKOUT_EXPIRED,
        CHECKOUT_ALREADY_CONSUMED,
        INVALID_COMPOSITION,
        AMOUNT_MISMATCH,
        SOLD_OUT_OR_PROCESSING,
        DUPLICATE_REQUEST_PROCESSING,
        REDIS_UNAVAILABLE
    }
}
