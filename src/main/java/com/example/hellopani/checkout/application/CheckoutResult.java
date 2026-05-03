package com.example.hellopani.checkout.application;

import java.time.LocalDateTime;
import com.example.hellopani.catalog.domain.Product;

public record CheckoutResult(
        String checkoutId,
        Product product,
        long availablePoint,
        LocalDateTime expiresAt
) {
}
