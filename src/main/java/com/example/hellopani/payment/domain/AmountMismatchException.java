package com.example.hellopani.payment.domain;

public class AmountMismatchException extends RuntimeException {

    public AmountMismatchException(String message) {
        super(message);
    }
}
