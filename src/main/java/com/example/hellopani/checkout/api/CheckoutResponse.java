package com.example.hellopani.checkout.api;

import java.time.LocalDateTime;
import com.example.hellopani.catalog.domain.Product;
import com.example.hellopani.checkout.application.CheckoutResult;

public record CheckoutResponse(
        String checkoutId,
        ProductSummary product,
        long availablePoint,
        LocalDateTime expiresAt
) {

    static CheckoutResponse from(CheckoutResult result) {
        Product product = result.product();
        ProductSummary summary = new ProductSummary(
                product.name(),
                product.price(),
                product.imageUrl(),
                product.checkInAt(),
                product.checkOutAt()
        );
        return new CheckoutResponse(
                result.checkoutId(),
                summary,
                result.availablePoint(),
                result.expiresAt()
        );
    }

    public record ProductSummary(
            String name,
            long price,
            String imageUrl,
            LocalDateTime checkInAt,
            LocalDateTime checkOutAt
    ) {
    }
}
