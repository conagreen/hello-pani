package com.example.hellopani.checkout.application;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.TestPropertySource;
import com.example.hellopani.checkout.domain.ProductNotFoundException;
import com.example.hellopani.checkout.infra.CheckoutCache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@TestPropertySource(properties = "spring.main.allow-bean-definition-overriding=true")
@Import(CheckoutServiceTest.FixedClockConfig.class)
@DisplayName("CheckoutService — Redis cache 기반 주문서 발급 (DB INSERT 없음)")
class CheckoutServiceTest {

    static final Instant FIXED_INSTANT = Instant.parse("2026-05-01T00:00:00Z");
    static final LocalDateTime FIXED_NOW = LocalDateTime.ofInstant(FIXED_INSTANT, ZoneId.of("UTC"));

    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        Clock clock() {
            return Clock.fixed(FIXED_INSTANT, ZoneId.of("UTC"));
        }
    }

    @Autowired
    CheckoutService checkoutService;

    @Autowired
    CheckoutCache checkoutCache;

    @Autowired
    StringRedisTemplate redisTemplate;

    @AfterEach
    void cleanup() {
        Set<String> keys = redisTemplate.keys("checkout:*");
        if (keys != null && !keys.isEmpty()) redisTemplate.delete(keys);
    }

    @Test
    @DisplayName("정상 주문서 발급 시 product / availablePoint / expiresAt(=now+10분) 모두 반환된다")
    void issuesCheckoutWithExpectedFields() {
        CheckoutResult result = checkoutService.issue("test-user-1", 1L);

        assertThat(result.checkoutId()).isNotBlank();
        assertThat(result.product().productId()).isEqualTo(1L);
        assertThat(result.product().price()).isEqualTo(150000L);
        assertThat(result.availablePoint()).isEqualTo(50000L);
        assertThat(result.expiresAt()).isEqualTo(FIXED_NOW.plusMinutes(10));
    }

    @Test
    @DisplayName("발급된 checkoutId는 Redis cache에 userId 매핑으로 적재된다 (DB INSERT 없음)")
    void persistsCheckoutToRedisCacheOnly() {
        CheckoutResult result = checkoutService.issue("test-user-1", 1L);

        // Redis cache에 userId 매핑 적재 확인
        assertThat(checkoutCache.findUserId(result.checkoutId())).contains("test-user-1");
        // DB에는 INSERT되지 않았음 — booking transaction이 게이트 통과 시에만 INSERT
        // (직접 DB 조회는 BookingControllerTest에서 검증)
    }

    @Test
    @DisplayName("존재하지 않는 productId는 ProductNotFoundException을 던진다")
    void throwsWhenProductMissing() {
        assertThatThrownBy(() -> checkoutService.issue("test-user-1", 999L))
                .isInstanceOf(ProductNotFoundException.class);
    }

    @Test
    @DisplayName("PointAccount가 없는 신규 사용자도 availablePoint=0으로 정상 발급된다")
    void treatsMissingPointAccountAsZeroBalance() {
        CheckoutResult result = checkoutService.issue("brand-new-user", 1L);

        assertThat(result.availablePoint()).isZero();
        // Redis cache에는 userId만 매핑되며 잔액 정보는 저장하지 않는다 (POST 시점에 재조회).
        assertThat(checkoutCache.findUserId(result.checkoutId())).contains("brand-new-user");
    }

    @Test
    @DisplayName("GET Checkout은 비멱등: 같은 사용자가 다시 호출하면 새 checkoutId가 발급된다")
    void issuesDifferentCheckoutIdEachCall() {
        CheckoutResult first = checkoutService.issue("test-user-1", 1L);
        CheckoutResult second = checkoutService.issue("test-user-1", 1L);

        assertThat(first.checkoutId()).isNotEqualTo(second.checkoutId());
    }

    @Test
    @DisplayName("Redis cache TTL은 expiresAt까지(약 10분)로 설정된다")
    void cacheTtlMatchesExpiry() {
        CheckoutResult result = checkoutService.issue("test-user-1", 1L);

        Long ttlSeconds = redisTemplate.getExpire("checkout:" + result.checkoutId());
        assertThat(ttlSeconds).isNotNull();
        assertThat(ttlSeconds).isPositive();
        // Duration.ofMinutes(10) = 600 seconds. -1초 / +1초 정도 오차 허용.
        assertThat(ttlSeconds).isBetween(595L, 605L);
    }
}
