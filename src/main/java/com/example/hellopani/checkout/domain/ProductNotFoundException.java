package com.example.hellopani.checkout.domain;

public class ProductNotFoundException extends RuntimeException {

    public ProductNotFoundException(long productId) {
        super("Product not found: " + productId);
    }
}
