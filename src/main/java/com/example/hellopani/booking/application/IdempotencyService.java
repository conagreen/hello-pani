package com.example.hellopani.booking.application;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import java.time.Duration;
import java.util.Optional;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import com.example.hellopani.inventory.domain.RedisUnavailableException;

@Component
public class IdempotencyService {

    private static final Duration TTL = Duration.ofHours(24);
    private static final String VALUE_PROCESSING = "processing";
    private static final String VALUE_DONE = "done";

    private final StringRedisTemplate redisTemplate;

    public IdempotencyService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @CircuitBreaker(name = "redis", fallbackMethod = "tryAcquireFallback")
    public IdempotencyAcquisition tryAcquire(String checkoutId) {
        Boolean acquired = redisTemplate.opsForValue()
                .setIfAbsent(stateKey(checkoutId), VALUE_PROCESSING, TTL);
        if (Boolean.TRUE.equals(acquired)) {
            return IdempotencyAcquisition.ACQUIRED;
        }
        String existing = redisTemplate.opsForValue().get(stateKey(checkoutId));
        if (VALUE_DONE.equals(existing)) {
            return IdempotencyAcquisition.ALREADY_DONE;
        }
        return IdempotencyAcquisition.ALREADY_PROCESSING;
    }

    @SuppressWarnings("unused")
    private IdempotencyAcquisition tryAcquireFallback(String checkoutId, Throwable t) {
        throw new RedisUnavailableException("Redis idempotency unavailable (timeout or circuit open)", t);
    }

    public void release(String checkoutId) {
        redisTemplate.delete(stateKey(checkoutId));
        redisTemplate.delete(resultKey(checkoutId));
    }

    public void completeWithResult(String checkoutId, String resultJson) {
        redisTemplate.opsForValue().set(resultKey(checkoutId), resultJson, TTL);
        redisTemplate.opsForValue().set(stateKey(checkoutId), VALUE_DONE, TTL);
    }

    public Optional<String> findCachedResult(String checkoutId) {
        return Optional.ofNullable(redisTemplate.opsForValue().get(resultKey(checkoutId)));
    }

    static String stateKey(String checkoutId) {
        return "idempotency:" + checkoutId;
    }

    static String resultKey(String checkoutId) {
        return "idempotency:result:" + checkoutId;
    }
}
