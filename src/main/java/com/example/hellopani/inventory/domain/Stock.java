package com.example.hellopani.inventory.domain;

import java.time.LocalDateTime;

public record Stock(
        long productId,
        int qty,
        LocalDateTime updatedAt
) {
}
