package com.example.hellopani.booking.application;

import java.time.Duration;
import java.util.Optional;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class IdempotencyService {

    private static final Duration TTL = Duration.ofHours(24);
    private static final String VALUE_PROCESSING = "processing";
    private static final String VALUE_DONE = "done";

    private final StringRedisTemplate redisTemplate;

    public IdempotencyService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

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
