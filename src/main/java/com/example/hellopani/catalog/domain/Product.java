package com.example.hellopani.catalog.domain;

import java.time.LocalDateTime;

public record Product(
        long productId,
        String name,
        long price,
        String imageUrl,
        LocalDateTime checkInAt,
        LocalDateTime checkOutAt,
        LocalDateTime salesOpenAt
) {
}
