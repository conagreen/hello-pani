package com.example.hellopani.booking.api;

import java.time.LocalDateTime;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import com.example.hellopani.checkout.infra.CheckoutCache;
import com.example.hellopani.inventory.domain.RedisUnavailableException;
import com.example.hellopani.inventory.domain.StockGate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("Redis 장애 fail-fast — RedisUnavailableException 시 503 + DB 우회 차감 없음")
class RedisFailFastTest {

    private static final AtomicLong COUNTER = new AtomicLong();

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    StringRedisTemplate redisTemplate;

    @MockitoBean
    StockGate stockGate;

    @Autowired
    CheckoutCache checkoutCache;

    @BeforeEach
    void resetState() {
        jdbcTemplate.update("UPDATE stock SET qty = 10 WHERE product_id = 1");
        redisTemplate.opsForValue().set("stock:1", "10");
        Set<String> idem = redisTemplate.keys("idempotency:*");
        if (idem != null && !idem.isEmpty()) redisTemplate.delete(idem);
    }

    @AfterEach
    void cleanup() {
        jdbcTemplate.update("DELETE FROM compensation_step WHERE checkout_id LIKE 'ck-redfail-%'");
        jdbcTemplate.update("DELETE FROM payment_component WHERE payment_id IN "
                + "(SELECT payment_id FROM payment WHERE checkout_id LIKE 'ck-redfail-%')");
        jdbcTemplate.update("DELETE FROM payment WHERE checkout_id LIKE 'ck-redfail-%'");
        jdbcTemplate.update("DELETE FROM booking WHERE checkout_id LIKE 'ck-redfail-%'");
        jdbcTemplate.update("DELETE FROM checkout WHERE checkout_id LIKE 'ck-redfail-%'");
        jdbcTemplate.update("UPDATE stock SET qty = 10 WHERE product_id = 1");
    }

    @Test
    @DisplayName("[완료조건] StockGate.tryAcquire에서 RedisUnavailableException → 503 + DB stock 미변경 (DB 우회 없음)")
    void redisUnavailable_returns503_andDoesNotBypassToDb() throws Exception {
        String checkoutId = "ck-redfail-" + System.nanoTime() + "-" + COUNTER.incrementAndGet();
        // 새 모델: GET /checkout이 Redis cache에 SET. DB INSERT는 booking 시점.
        // 이 테스트는 stockGate에서 Redis 장애를 시뮬레이션하므로 cache는 정상 작동 가정 (cache.put은 normal Redis call).
        checkoutCache.put(checkoutId, "test-user-1", java.time.Duration.ofMinutes(10));

        when(stockGate.tryAcquire(anyLong(), anyString(), anyString()))
                .thenThrow(new RedisUnavailableException("simulated outage", null));

        String body = """
                {"checkoutId":"%s","productId":1,"payments":[{"method":"CARD","amount":150000}]}
                """.formatted(checkoutId);

        mockMvc.perform(post("/bookings")
                        .header("X-User-Id", "test-user-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("REDIS_UNAVAILABLE"))
                .andExpect(jsonPath("$.retryable").value(true))
                .andExpect(jsonPath("$.retryAfterSeconds").isNumber())
                .andExpect(header().exists("Retry-After"))
                .andExpect(header().string("Retry-After", String.valueOf(5)));

        // DB stock 그대로 — 어떤 우회 차감도 발생하지 않음
        Integer stockQty = jdbcTemplate.queryForObject(
                "SELECT qty FROM stock WHERE product_id = 1", Integer.class);
        assertThat(stockQty).isEqualTo(10);

        // booking/payment 미생성
        Integer bookingCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM booking WHERE checkout_id = ?", Integer.class, checkoutId);
        assertThat(bookingCount).isZero();
        Integer paymentCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM payment WHERE checkout_id = ?", Integer.class, checkoutId);
        assertThat(paymentCount).isZero();

        // idempotency 키도 release되어 재시도 가능
        assertThat(redisTemplate.hasKey("idempotency:" + checkoutId)).isFalse();
    }
}
