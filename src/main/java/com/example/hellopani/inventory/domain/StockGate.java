package com.example.hellopani.inventory.domain;

public interface StockGate {

    GateAcquireResult tryAcquire(long productId, String userId, String checkoutId);

    void release(long productId, String checkoutId);
}
