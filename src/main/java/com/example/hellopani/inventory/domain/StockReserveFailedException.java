package com.example.hellopani.inventory.domain;

public class StockReserveFailedException extends RuntimeException {

    public StockReserveFailedException(long productId) {
        super("DB stock reserve failed for productId=" + productId);
    }
}
