package com.example.hellopani.checkout.infra;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import java.time.Duration;
import java.util.Optional;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import com.example.hellopani.inventory.domain.RedisUnavailableException;

/**
 * Checkout 발급 후 POST /bookings에서 다시 사용될 때까지 *최소한*의 검증값(userId)만 Redis에 둔다.
 *
 * <p>설계 의도:
 * <ul>
 *   <li>GET /checkout이 DB write를 하지 않는다 → 거절 경로 0 DB hit</li>
 *   <li>나머지 검증값(가격, 포인트 잔액)은 POST 시점에 product / point_account를 재조회해 얻는다</li>
 *   <li>만료는 Redis TTL이 자동 처리 — checkoutId가 cache에 있으면 valid, 없으면 invalid (NULL = expired)</li>
 *   <li>userId만 매핑해 두는 이유: 다른 사용자가 checkoutId를 도용해도 매핑된 userId와 X-User-Id가 다르면 거절</li>
 * </ul>
 *
 * <p>Redis 장애 시 idempotency / stock gate와 마찬가지로 fail-fast.
 */
@Component
public class CheckoutCache {

    private static final String KEY_PREFIX = "checkout:";

    private final StringRedisTemplate redisTemplate;

    public CheckoutCache(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @CircuitBreaker(name = "redis", fallbackMethod = "putFallback")
    public void put(String checkoutId, String userId, Duration ttl) {
        redisTemplate.opsForValue().set(key(checkoutId), userId, ttl);
    }

    @SuppressWarnings("unused")
    private void putFallback(String checkoutId, String userId, Duration ttl, Throwable t) {
        throw new RedisUnavailableException("Redis checkout cache unavailable on put", t);
    }

    @CircuitBreaker(name = "redis", fallbackMethod = "findFallback")
    public Optional<String> findUserId(String checkoutId) {
        return Optional.ofNullable(redisTemplate.opsForValue().get(key(checkoutId)));
    }

    @SuppressWarnings("unused")
    private Optional<String> findFallback(String checkoutId, Throwable t) {
        throw new RedisUnavailableException("Redis checkout cache unavailable on find", t);
    }

    /**
     * booking transaction이 DB 영속화를 마친 뒤 호출해 Redis 매핑을 즉시 정리한다.
     * 호출되지 않더라도 TTL로 자연 만료된다.
     */
    public void evict(String checkoutId) {
        try {
            redisTemplate.delete(key(checkoutId));
        } catch (RuntimeException ignored) {
            // 정리 실패는 TTL이 처리한다.
        }
    }

    private static String key(String checkoutId) {
        return KEY_PREFIX + checkoutId;
    }
}
