package com.example.hellopani.inventory.domain;

public sealed interface GateAcquireResult {

    static GateAcquireResult acquired() {
        return Acquired.INSTANCE;
    }

    static GateAcquireResult rejected(GateRejectionReason reason, int retryAfterSeconds) {
        return new Rejected(reason, retryAfterSeconds);
    }

    record Acquired() implements GateAcquireResult {
        static final Acquired INSTANCE = new Acquired();
    }

    record Rejected(GateRejectionReason reason, int retryAfterSeconds) implements GateAcquireResult {
    }
}
