package com.example.hellopani.checkout.domain;

public class CheckoutOwnershipMismatchException extends RuntimeException {

    public CheckoutOwnershipMismatchException(String checkoutId) {
        super("Checkout user mismatch: " + checkoutId);
    }
}
