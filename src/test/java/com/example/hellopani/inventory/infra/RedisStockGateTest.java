package com.example.hellopani.inventory.infra;

import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import com.example.hellopani.inventory.domain.GateAcquireResult;
import com.example.hellopani.inventory.domain.GateRejectionReason;
import com.example.hellopani.inventory.domain.StockGate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@DisplayName("RedisStockGate — Lua 원자 acquire/release와 hold 멱등")
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
    @DisplayName("첫 acquire는 stock 카운터를 1 차감하고 hold HASH(productId, userId)를 TTL과 함께 생성한다")
    void acquireSucceedsForFirstRequest() {
        GateAcquireResult result = stockGate.tryAcquire(1L, "test-user-1", "ck-first");

        assertThat(result).isInstanceOf(GateAcquireResult.Acquired.class);
        assertThat(redisTemplate.opsForValue().get("stock:1")).isEqualTo("9");
        assertThat(redisTemplate.opsForHash().get("hold:ck-first", "productId")).isEqualTo("1");
        assertThat(redisTemplate.opsForHash().get("hold:ck-first", "userId")).isEqualTo("test-user-1");
        assertThat(redisTemplate.getExpire("hold:ck-first")).isPositive();
    }

    @Test
    @DisplayName("같은 checkoutId 재시도는 hold가 있으면 멱등 통과하고 stock을 다시 차감하지 않는다")
    void acquireIsIdempotentForSameCheckoutId() {
        stockGate.tryAcquire(1L, "test-user-1", "ck-idem");
        GateAcquireResult second = stockGate.tryAcquire(1L, "test-user-1", "ck-idem");

        assertThat(second).isInstanceOf(GateAcquireResult.Acquired.class);
        assertThat(redisTemplate.opsForValue().get("stock:1")).isEqualTo("9");
    }

    @Test
    @DisplayName("10번 acquire 후 11번째는 SOLD_OUT_OR_PROCESSING(retryable=true, retryAfterSeconds>0)으로 거절된다")
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
    @DisplayName("release는 stock을 다시 1 증가시키고 hold 키를 제거한다 (보상 경로)")
    void releaseRestoresStockAndDeletesHold() {
        stockGate.tryAcquire(1L, "u", "ck-rel");
        assertThat(redisTemplate.opsForValue().get("stock:1")).isEqualTo("9");

        stockGate.release(1L, "ck-rel");

        assertThat(redisTemplate.opsForValue().get("stock:1")).isEqualTo("10");
        assertThat(redisTemplate.hasKey("hold:ck-rel")).isFalse();
    }

    @Test
    @DisplayName("hold가 없는 checkoutId에 대한 release는 noop이며 stock을 변경하지 않는다")
    void releaseIsNoopWhenNoHoldExists() {
        stockGate.release(1L, "ck-never-acquired");

        assertThat(redisTemplate.opsForValue().get("stock:1")).isEqualTo("10");
    }

    @Test
    @DisplayName("같은 checkoutId로 release를 두 번 호출해도 stock이 중복 증가하지 않는다 (Lua 자체 멱등)")
    void releaseTwiceDoesNotDoubleIncrement() {
        stockGate.tryAcquire(1L, "u", "ck-twice");
        stockGate.release(1L, "ck-twice");
        stockGate.release(1L, "ck-twice");

        assertThat(redisTemplate.opsForValue().get("stock:1")).isEqualTo("10");
        assertThat(redisTemplate.hasKey("hold:ck-twice")).isFalse();
    }

    @Test
    @DisplayName("stock 카운터 자체가 없는 상태에서는 acquire가 SOLD_OUT으로 거절된다 (오픈 전)")
    void rejectsWhenNoStockCounterExists() {
        redisTemplate.delete("stock:1");

        GateAcquireResult result = stockGate.tryAcquire(1L, "u", "ck-no-counter");

        assertThat(result).isInstanceOf(GateAcquireResult.Rejected.class);
    }
}
