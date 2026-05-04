package com.example.hellopani.inventory.infra;

import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import com.example.hellopani.inventory.domain.GateAcquireResult;
import com.example.hellopani.inventory.domain.GateRejectionReason;
import com.example.hellopani.inventory.domain.StockGate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class RedisStockGateTest {

    @Autowired
    StockGate stockGate;

    @Autowired
    StringRedisTemplate redisTemplate;

    @BeforeEach
    void resetRedis() {
        redisTemplate.opsForValue().set("stock:1", "10");
        Set<String> holds = redisTemplate.keys("hold:*");
        if (holds != null && !holds.isEmpty()) {
            redisTemplate.delete(holds);
        }
    }

    @Test
    void acquireSucceedsForFirstRequest() {
        GateAcquireResult result = stockGate.tryAcquire(1L, "test-user-1", "ck-first");

        assertThat(result).isInstanceOf(GateAcquireResult.Acquired.class);
        assertThat(redisTemplate.opsForValue().get("stock:1")).isEqualTo("9");
        assertThat(redisTemplate.opsForHash().get("hold:ck-first", "productId")).isEqualTo("1");
        assertThat(redisTemplate.opsForHash().get("hold:ck-first", "userId")).isEqualTo("test-user-1");
        assertThat(redisTemplate.getExpire("hold:ck-first")).isPositive();
    }

    @Test
    void acquireIsIdempotentForSameCheckoutId() {
        stockGate.tryAcquire(1L, "test-user-1", "ck-idem");
        GateAcquireResult second = stockGate.tryAcquire(1L, "test-user-1", "ck-idem");

        assertThat(second).isInstanceOf(GateAcquireResult.Acquired.class);
        assertThat(redisTemplate.opsForValue().get("stock:1")).isEqualTo("9");
    }

    @Test
    void rejectsEleventhRequest() {
        for (int i = 0; i < 10; i++) {
            GateAcquireResult r = stockGate.tryAcquire(1L, "u", "ck-drain-" + i);
            assertThat(r).isInstanceOf(GateAcquireResult.Acquired.class);
        }

        GateAcquireResult eleventh = stockGate.tryAcquire(1L, "u", "ck-drain-10");

        assertThat(eleventh).isInstanceOf(GateAcquireResult.Rejected.class);
        GateAcquireResult.Rejected rejected = (GateAcquireResult.Rejected) eleventh;
        assertThat(rejected.reason()).isEqualTo(GateRejectionReason.SOLD_OUT_OR_PROCESSING);
        assertThat(rejected.retryable()).isTrue();
        assertThat(rejected.retryAfterSeconds()).isPositive();
        assertThat(redisTemplate.opsForValue().get("stock:1")).isEqualTo("0");
    }

    @Test
    void releaseRestoresStockAndDeletesHold() {
        stockGate.tryAcquire(1L, "u", "ck-rel");
        assertThat(redisTemplate.opsForValue().get("stock:1")).isEqualTo("9");

        stockGate.release(1L, "ck-rel");

        assertThat(redisTemplate.opsForValue().get("stock:1")).isEqualTo("10");
        assertThat(redisTemplate.hasKey("hold:ck-rel")).isFalse();
    }

    @Test
    void releaseIsNoopWhenNoHoldExists() {
        stockGate.release(1L, "ck-never-acquired");

        assertThat(redisTemplate.opsForValue().get("stock:1")).isEqualTo("10");
    }

    @Test
    void releaseTwiceDoesNotDoubleIncrement() {
        stockGate.tryAcquire(1L, "u", "ck-twice");
        stockGate.release(1L, "ck-twice");
        stockGate.release(1L, "ck-twice");

        assertThat(redisTemplate.opsForValue().get("stock:1")).isEqualTo("10");
        assertThat(redisTemplate.hasKey("hold:ck-twice")).isFalse();
    }

    @Test
    void rejectsWhenNoStockCounterExists() {
        redisTemplate.delete("stock:1");

        GateAcquireResult result = stockGate.tryAcquire(1L, "u", "ck-no-counter");

        assertThat(result).isInstanceOf(GateAcquireResult.Rejected.class);
    }
}
