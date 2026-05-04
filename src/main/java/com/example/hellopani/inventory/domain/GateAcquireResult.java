package com.example.hellopani.inventory.domain;

public sealed interface GateAcquireResult {

    static GateAcquireResult acquired() {
        return Acquired.INSTANCE;
    }

    static GateAcquireResult rejected(GateRejectionReason reason, int retryAfterSeconds) {
        return new Rejected(reason, true, retryAfterSeconds);
    }

    record Acquired() implements GateAcquireResult {
        static final Acquired INSTANCE = new Acquired();
    }

    record Rejected(GateRejectionReason reason, boolean retryable, int retryAfterSeconds) implements GateAcquireResult {
    }
}
