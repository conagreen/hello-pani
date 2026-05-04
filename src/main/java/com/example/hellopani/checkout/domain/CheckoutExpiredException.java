package com.example.hellopani.checkout.domain;

public class CheckoutExpiredException extends RuntimeException {

    public CheckoutExpiredException(String checkoutId) {
        super("Checkout expired: " + checkoutId);
    }
}
