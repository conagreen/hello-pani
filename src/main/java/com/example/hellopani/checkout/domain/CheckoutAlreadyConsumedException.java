package com.example.hellopani.checkout.domain;

public class CheckoutAlreadyConsumedException extends RuntimeException {

    public CheckoutAlreadyConsumedException(String checkoutId) {
        super("Checkout already used or expired: " + checkoutId);
    }
}
