package com.example.hellopani.inventory.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.inventory")
public record InventoryProperties(
        int holdTtlMinutes,
        int soldOutRetryAfterSeconds
) {
}
