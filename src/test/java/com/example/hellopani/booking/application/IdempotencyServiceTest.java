package com.example.hellopani.booking.application;

import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@DisplayName("IdempotencyService — Redis SETNX 점유와 응답 캐시")
class IdempotencyServiceTest {

    @Autowired
    IdempotencyService idempotencyService;

    @Autowired
    StringRedisTemplate redisTemplate;

    @BeforeEach
    void resetRedis() {
        Set<String> keys = redisTemplate.keys("idempotency:*");
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }

    @Test
    @DisplayName("처음 호출은 SETNX로 점유에 성공해 ACQUIRED를 반환한다")
    void firstAcquireReturnsAcquired() {
        IdempotencyAcquisition result = idempotencyService.tryAcquire("ck-1");

        assertThat(result).isEqualTo(IdempotencyAcquisition.ACQUIRED);
        assertThat(redisTemplate.opsForValue().get("idempotency:ck-1")).isEqualTo("processing");
    }

    @Test
    @DisplayName("같은 checkoutId 재호출 + 처리 중 상태는 ALREADY_PROCESSING을 반환한다 (동시 요청 차단)")
    void secondAcquireWhileProcessingReturnsAlreadyProcessing() {
        idempotencyService.tryAcquire("ck-2");

        IdempotencyAcquisition result = idempotencyService.tryAcquire("ck-2");

        assertThat(result).isEqualTo(IdempotencyAcquisition.ALREADY_PROCESSING);
    }

    @Test
    @DisplayName("completeWithResult 후 재호출은 ALREADY_DONE을 반환하고 캐시된 결과를 조회할 수 있다")
    void acquireAfterCompletionReturnsAlreadyDone() {
        idempotencyService.tryAcquire("ck-3");
        idempotencyService.completeWithResult("ck-3", "{\"status\":\"CONFIRMED\"}");

        IdempotencyAcquisition result = idempotencyService.tryAcquire("ck-3");

        assertThat(result).isEqualTo(IdempotencyAcquisition.ALREADY_DONE);
        Optional<String> cached = idempotencyService.findCachedResult("ck-3");
        assertThat(cached).contains("{\"status\":\"CONFIRMED\"}");
    }

    @Test
    @DisplayName("release는 상태 키와 결과 키를 모두 제거해 재요청을 허용한다 (검증 실패 경로)")
    void releaseRemovesBothStateAndResultKeys() {
        idempotencyService.tryAcquire("ck-4");
        idempotencyService.completeWithResult("ck-4", "{\"status\":\"FAILED\"}");

        idempotencyService.release("ck-4");

        assertThat(redisTemplate.hasKey("idempotency:ck-4")).isFalse();
        assertThat(redisTemplate.hasKey("idempotency:result:ck-4")).isFalse();
        assertThat(idempotencyService.tryAcquire("ck-4")).isEqualTo(IdempotencyAcquisition.ACQUIRED);
    }

    @Test
    @DisplayName("캐시된 결과가 없는 checkoutId 조회는 빈 Optional을 반환한다")
    void findCachedResultReturnsEmptyForUnknownCheckout() {
        assertThat(idempotencyService.findCachedResult("ck-unknown")).isEmpty();
    }

    @Test
    @DisplayName("점유된 키에는 24시간 TTL이 부여된다")
    void acquiredKeyHasTtl() {
        idempotencyService.tryAcquire("ck-ttl");

        Long ttl = redisTemplate.getExpire("idempotency:ck-ttl");
        assertThat(ttl).isPositive();
    }
}
