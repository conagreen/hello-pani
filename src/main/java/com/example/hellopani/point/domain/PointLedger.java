package com.example.hellopani.point.domain;

import java.time.LocalDateTime;

public record PointLedger(
        long pointLedgerId,
        String userId,
        String checkoutId,
        long amount,
        PointReason reason,
        LocalDateTime createdAt
) {
}
