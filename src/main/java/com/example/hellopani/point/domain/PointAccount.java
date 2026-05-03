package com.example.hellopani.point.domain;

import java.time.LocalDateTime;

public record PointAccount(
        String userId,
        long balance,
        LocalDateTime updatedAt
) {
}
