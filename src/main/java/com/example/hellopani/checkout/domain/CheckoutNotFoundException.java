package com.example.hellopani.checkout.domain;

public class CheckoutNotFoundException extends RuntimeException {

    public CheckoutNotFoundException(String checkoutId) {
        super("Checkout not found: " + checkoutId);
    }
}
