package com.example.hellopani.inventory.infra;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import java.time.Duration;
import java.util.List;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.scripting.support.ResourceScriptSource;
import org.springframework.stereotype.Component;
import com.example.hellopani.inventory.application.InventoryProperties;
import com.example.hellopani.inventory.domain.GateAcquireResult;
import com.example.hellopani.inventory.domain.GateRejectionReason;
import com.example.hellopani.inventory.domain.RedisUnavailableException;
import com.example.hellopani.inventory.domain.StockGate;

@Component
public class RedisStockGate implements StockGate {

    private static final RedisScript<Long> ACQUIRE_SCRIPT = buildScript("scripts/inventory/acquire.lua");
    private static final RedisScript<Long> RELEASE_SCRIPT = buildScript("scripts/inventory/release.lua");

    private final StringRedisTemplate redisTemplate;
    private final InventoryProperties properties;

    public RedisStockGate(StringRedisTemplate redisTemplate, InventoryProperties properties) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
    }

    @Override
    @CircuitBreaker(name = "redis", fallbackMethod = "tryAcquireFallback")
    public GateAcquireResult tryAcquire(long productId, String userId, String checkoutId) {
        long holdTtlMillis = Duration.ofMinutes(properties.holdTtlMinutes()).toMillis();
        Long result = redisTemplate.execute(
                ACQUIRE_SCRIPT,
                List.of(stockKey(productId), holdKey(checkoutId)),
                Long.toString(productId),
                userId,
                Long.toString(holdTtlMillis));
        if (result != null && result == 1L) {
            return GateAcquireResult.acquired();
        }
        return GateAcquireResult.rejected(
                GateRejectionReason.SOLD_OUT_OR_PROCESSING,
                properties.soldOutRetryAfterSeconds());
    }

    @SuppressWarnings("unused")
    private GateAcquireResult tryAcquireFallback(long productId, String userId, String checkoutId, Throwable t) {
        throw new RedisUnavailableException("Redis stock gate unavailable (timeout or circuit open)", t);
    }

    @Override
    public void release(long productId, String checkoutId) {
        // 보상 경로의 Redis 호출은 fail-fast로 사용자 응답에 노출하지 않는다.
        // 호출자(CompensationService)가 예외를 catch하여 step 미기록 + 재시도로 복구한다.
        redisTemplate.execute(
                RELEASE_SCRIPT,
                List.of(stockKey(productId), holdKey(checkoutId)));
    }

    static String stockKey(long productId) {
        return "stock:" + productId;
    }

    static String holdKey(String checkoutId) {
        return "hold:" + checkoutId;
    }

    private static RedisScript<Long> buildScript(String classpathLocation) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setScriptSource(new ResourceScriptSource(new ClassPathResource(classpathLocation)));
        script.setResultType(Long.class);
        return script;
    }
}
