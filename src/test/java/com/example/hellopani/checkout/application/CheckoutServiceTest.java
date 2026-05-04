package com.example.hellopani.checkout.application;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import com.example.hellopani.catalog.infra.ProductRepository;
import com.example.hellopani.checkout.domain.ProductNotFoundException;
import com.example.hellopani.checkout.infra.CheckoutRepository;
import com.example.hellopani.point.infra.PointRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = "spring.main.allow-bean-definition-overriding=true")
@Import({
        ProductRepository.class,
        PointRepository.class,
        CheckoutRepository.class,
        CheckoutService.class,
        CheckoutServiceTest.FixedClockConfig.class
})
@DisplayName("CheckoutService — 주문서 발급 흐름과 만료 시각 / 멱등 / 부재 사용자 처리")
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
    JdbcTemplate jdbcTemplate;

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
    @DisplayName("발급된 Checkout은 ISSUED 상태와 quoted_price, available_point_snapshot으로 영속화된다")
    void persistsCheckoutWithIssuedStatusAndQuotedPrice() {
        CheckoutResult result = checkoutService.issue("test-user-1", 1L);

        String status = jdbcTemplate.queryForObject(
                "SELECT status FROM checkout WHERE checkout_id = ?",
                String.class, result.checkoutId());
        assertThat(status).isEqualTo("ISSUED");

        Long quotedPrice = jdbcTemplate.queryForObject(
                "SELECT quoted_price FROM checkout WHERE checkout_id = ?",
                Long.class, result.checkoutId());
        assertThat(quotedPrice).isEqualTo(150000L);

        Long pointSnapshot = jdbcTemplate.queryForObject(
                "SELECT available_point_snapshot FROM checkout WHERE checkout_id = ?",
                Long.class, result.checkoutId());
        assertThat(pointSnapshot).isEqualTo(50000L);
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
        Long pointSnapshot = jdbcTemplate.queryForObject(
                "SELECT available_point_snapshot FROM checkout WHERE checkout_id = ?",
                Long.class, result.checkoutId());
        assertThat(pointSnapshot).isZero();
    }

    @Test
    @DisplayName("GET Checkout은 비멱등: 같은 사용자가 다시 호출하면 새 checkoutId가 발급된다")
    void issuesDifferentCheckoutIdEachCall() {
        CheckoutResult first = checkoutService.issue("test-user-1", 1L);
        CheckoutResult second = checkoutService.issue("test-user-1", 1L);

        assertThat(first.checkoutId()).isNotEqualTo(second.checkoutId());
    }

    @Test
    @DisplayName("발급된 Checkout 행에 user_id, product_id가 그대로 영속화된다")
    void persistsSubmittedUserIdAndProductId() {
        CheckoutResult result = checkoutService.issue("brand-new-user", 1L);

        String userId = jdbcTemplate.queryForObject(
                "SELECT user_id FROM checkout WHERE checkout_id = ?",
                String.class, result.checkoutId());
        assertThat(userId).isEqualTo("brand-new-user");

        Long productId = jdbcTemplate.queryForObject(
                "SELECT product_id FROM checkout WHERE checkout_id = ?",
                Long.class, result.checkoutId());
        assertThat(productId).isEqualTo(1L);
    }
}
