package com.example.hellopani.checkout.application;

import com.example.hellopani.catalog.domain.Product;

import java.time.LocalDateTime;

public record CheckoutResult(
        String checkoutId,
        Product product,
        long availablePoint,
        LocalDateTime expiresAt
) {
}
