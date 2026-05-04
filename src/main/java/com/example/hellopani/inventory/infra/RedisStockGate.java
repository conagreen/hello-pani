package com.example.hellopani.inventory.infra;

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

    @Override
    public void release(long productId, String checkoutId) {
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
