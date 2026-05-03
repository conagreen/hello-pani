package com.example.hellopani.checkout.domain;

import java.time.LocalDateTime;

public record Checkout(
        String checkoutId,
        String userId,
        long productId,
        long quotedPrice,
        long availablePointSnapshot,
        CheckoutStatus status,
        LocalDateTime expiresAt,
        LocalDateTime createdAt
) {
}
