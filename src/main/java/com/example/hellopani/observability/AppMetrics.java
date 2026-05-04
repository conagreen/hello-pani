package com.example.hellopani.observability;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;
import com.example.hellopani.payment.domain.FailureReason;

@Component
public class AppMetrics {

    private final MeterRegistry registry;

    public AppMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void redisGateSuccess() {
        registry.counter("redis.gate.success").increment();
    }

    public void redisGateRejected() {
        registry.counter("redis.gate.failure", "reason", "SOLD_OUT_OR_PROCESSING").increment();
    }

    public void dbStockReserveSuccess() {
        registry.counter("db.stock.reserve.success").increment();
    }

    public void dbStockReserveFailure() {
        registry.counter("db.stock.reserve.failure", "reason", "SOLD_OUT").increment();
    }

    public void bookingConfirmed() {
        registry.counter("booking.confirmed").increment();
    }

    public void paymentFailure(FailureReason reason) {
        registry.counter("payment.failure", "reason", reason.name()).increment();
    }

    public void http503RedisUnavailable() {
        registry.counter("http.503", "reason", "REDIS_UNAVAILABLE").increment();
    }
}
