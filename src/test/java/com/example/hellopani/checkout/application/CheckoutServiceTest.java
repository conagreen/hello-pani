package com.example.hellopani.checkout.application;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import com.example.hellopani.catalog.infra.ProductRepository;
import com.example.hellopani.checkout.domain.ProductNotFoundException;
import com.example.hellopani.checkout.infra.CheckoutRepository;
import com.example.hellopani.point.infra.PointRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
        ProductRepository.class,
        PointRepository.class,
        CheckoutRepository.class,
        CheckoutService.class,
        CheckoutServiceTest.FixedClockConfig.class
})
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
    void issuesCheckoutWithExpectedFields() {
        CheckoutResult result = checkoutService.issue("test-user-1", 1L);

        assertThat(result.checkoutId()).isNotBlank();
        assertThat(result.product().productId()).isEqualTo(1L);
        assertThat(result.product().price()).isEqualTo(150000L);
        assertThat(result.availablePoint()).isEqualTo(50000L);
        assertThat(result.expiresAt()).isEqualTo(FIXED_NOW.plusMinutes(10));
    }

    @Test
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
    void throwsWhenProductMissing() {
        assertThatThrownBy(() -> checkoutService.issue("test-user-1", 999L))
                .isInstanceOf(ProductNotFoundException.class);
    }

    @Test
    void treatsMissingPointAccountAsZeroBalance() {
        CheckoutResult result = checkoutService.issue("brand-new-user", 1L);

        assertThat(result.availablePoint()).isZero();
        Long pointSnapshot = jdbcTemplate.queryForObject(
                "SELECT available_point_snapshot FROM checkout WHERE checkout_id = ?",
                Long.class, result.checkoutId());
        assertThat(pointSnapshot).isZero();
    }

    @Test
    void issuesDifferentCheckoutIdEachCall() {
        CheckoutResult first = checkoutService.issue("test-user-1", 1L);
        CheckoutResult second = checkoutService.issue("test-user-1", 1L);

        assertThat(first.checkoutId()).isNotEqualTo(second.checkoutId());
    }

    @Test
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
