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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import com.example.hellopani.payment.infra.FakePgClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("POST /bookings — 예약/결제 통합 시나리오 (TASKS Task 6 완료 조건 6개)")
class BookingControllerTest {

    private static final AtomicLong COUNTER = new AtomicLong();

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    StringRedisTemplate redisTemplate;

    @Autowired
    FakePgClient fakePgClient;

    @BeforeEach
    void resetGlobalState() {
        redisTemplate.opsForValue().set("stock:1", "10");
        Set<String> holds = redisTemplate.keys("hold:*");
        if (holds != null && !holds.isEmpty()) redisTemplate.delete(holds);
        Set<String> idem = redisTemplate.keys("idempotency:*");
        if (idem != null && !idem.isEmpty()) redisTemplate.delete(idem);
        jdbcTemplate.update("UPDATE point_account SET balance = 50000 WHERE user_id = 'test-user-1'");
        jdbcTemplate.update("UPDATE stock SET qty = 10 WHERE product_id = 1");
        fakePgClient.reset();
    }

    @AfterEach
    void cleanupRows() {
        jdbcTemplate.update("DELETE FROM compensation_step WHERE checkout_id LIKE 'ck-bk-%'");
        jdbcTemplate.update("DELETE FROM payment_component WHERE payment_id IN "
                + "(SELECT payment_id FROM payment WHERE checkout_id LIKE 'ck-bk-%')");
        jdbcTemplate.update("DELETE FROM payment WHERE checkout_id LIKE 'ck-bk-%'");
        jdbcTemplate.update("DELETE FROM point_ledger WHERE checkout_id LIKE 'ck-bk-%'");
        jdbcTemplate.update("DELETE FROM booking WHERE checkout_id LIKE 'ck-bk-%'");
        jdbcTemplate.update("DELETE FROM checkout WHERE checkout_id LIKE 'ck-bk-%'");
        jdbcTemplate.update("UPDATE point_account SET balance = 50000 WHERE user_id = 'test-user-1'");
        jdbcTemplate.update("UPDATE stock SET qty = 10 WHERE product_id = 1");
    }

    private String createCheckout(long quotedPrice, LocalDateTime expiresAt, String userId) {
        String checkoutId = "ck-bk-" + System.nanoTime() + "-" + COUNTER.incrementAndGet();
        jdbcTemplate.update(
                "INSERT INTO checkout "
                        + "(checkout_id, user_id, product_id, quoted_price, available_point_snapshot, status, expires_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                checkoutId, userId, 1L, quotedPrice, 50000L, "ISSUED", expiresAt);
        return checkoutId;
    }

    private static String body(String checkoutId, String method, long amount) {
        return """
                {"checkoutId":"%s","payments":[{"method":"%s","amount":%d}]}
                """.formatted(checkoutId, method, amount);
    }

    @Test
    @DisplayName("[완료조건] 정상 Booking 성공 — 200 + CONFIRMED, DB stock 1 감소, Booking CONFIRMED, Checkout USED")
    void successfulBooking_returnsConfirmed_andUpdatesAllSideEffects() throws Exception {
        String checkoutId = createCheckout(150_000L, LocalDateTime.now().plusMinutes(10), "test-user-1");

        mockMvc.perform(post("/bookings")
                        .header("X-User-Id", "test-user-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(checkoutId, "CARD", 150_000L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.checkoutId").value(checkoutId))
                .andExpect(jsonPath("$.bookingId").isNumber())
                .andExpect(jsonPath("$.paymentId").isNumber());

        Integer stockQty = jdbcTemplate.queryForObject(
                "SELECT qty FROM stock WHERE product_id = 1", Integer.class);
        assertThat(stockQty).isEqualTo(9);

        String checkoutStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM checkout WHERE checkout_id = ?", String.class, checkoutId);
        assertThat(checkoutStatus).isEqualTo("USED");

        String bookingStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM booking WHERE checkout_id = ?", String.class, checkoutId);
        assertThat(bookingStatus).isEqualTo("CONFIRMED");

        String paymentStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM payment WHERE checkout_id = ?", String.class, checkoutId);
        assertThat(paymentStatus).isEqualTo("SUCCEEDED");

        assertThat(redisTemplate.opsForValue().get("stock:1")).isEqualTo("9");
        assertThat(redisTemplate.hasKey("hold:" + checkoutId)).isTrue();
        assertThat(redisTemplate.opsForValue().get("idempotency:" + checkoutId)).isEqualTo("done");
    }

    @Test
    @DisplayName("[완료조건] 같은 checkoutId 재요청 — 멱등 응답 재생, 결제는 1회만 발생")
    void duplicateRequestReplaysCachedResponse_andDoesNotChargeAgain() throws Exception {
        String checkoutId = createCheckout(150_000L, LocalDateTime.now().plusMinutes(10), "test-user-1");

        mockMvc.perform(post("/bookings")
                        .header("X-User-Id", "test-user-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(checkoutId, "CARD", 150_000L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"));

        MvcResult second = mockMvc.perform(post("/bookings")
                        .header("X-User-Id", "test-user-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(checkoutId, "CARD", 150_000L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.checkoutId").value(checkoutId))
                .andReturn();

        JsonNode body = objectMapper.readTree(second.getResponse().getContentAsString());
        assertThat(body.get("bookingId").asLong()).isPositive();

        Integer paymentCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM payment WHERE checkout_id = ?", Integer.class, checkoutId);
        assertThat(paymentCount).isEqualTo(1);
        Integer bookingCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM booking WHERE checkout_id = ?", Integer.class, checkoutId);
        assertThat(bookingCount).isEqualTo(1);

        Integer stockQty = jdbcTemplate.queryForObject(
                "SELECT qty FROM stock WHERE product_id = 1", Integer.class);
        assertThat(stockQty).isEqualTo(9);
    }

    @Test
    @DisplayName("[완료조건] checkoutId 사용자 불일치 — 403 + CHECKOUT_USER_MISMATCH, DB 미변경")
    void userMismatch_isForbidden() throws Exception {
        String checkoutId = createCheckout(150_000L, LocalDateTime.now().plusMinutes(10), "owner-user");

        mockMvc.perform(post("/bookings")
                        .header("X-User-Id", "different-user")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(checkoutId, "CARD", 150_000L)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CHECKOUT_USER_MISMATCH"))
                .andExpect(jsonPath("$.retryable").value(false));

        Integer stockQty = jdbcTemplate.queryForObject(
                "SELECT qty FROM stock WHERE product_id = 1", Integer.class);
        assertThat(stockQty).isEqualTo(10);
        Integer bookingCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM booking WHERE checkout_id = ?", Integer.class, checkoutId);
        assertThat(bookingCount).isZero();
        // idempotency 키는 검증 실패 시 삭제됨 → 재요청 가능
        assertThat(redisTemplate.hasKey("idempotency:" + checkoutId)).isFalse();
    }

    @Test
    @DisplayName("[완료조건] 만료된 checkout — 400 + CHECKOUT_EXPIRED, DB 미변경")
    void expiredCheckout_isRejected() throws Exception {
        String checkoutId = createCheckout(150_000L, LocalDateTime.now().minusMinutes(1), "test-user-1");

        mockMvc.perform(post("/bookings")
                        .header("X-User-Id", "test-user-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(checkoutId, "CARD", 150_000L)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CHECKOUT_EXPIRED"))
                .andExpect(jsonPath("$.retryable").value(false));

        Integer bookingCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM booking WHERE checkout_id = ?", Integer.class, checkoutId);
        assertThat(bookingCount).isZero();
    }

    @Test
    @DisplayName("[완료조건] Redis gate 실패 — 409 SOLD_OUT_OR_PROCESSING, retryAfterSeconds 포함, 잔여 재고 수치 미노출")
    void redisGateRejection_returnsConflict_withoutRevealingStock() throws Exception {
        redisTemplate.opsForValue().set("stock:1", "0");
        String checkoutId = createCheckout(150_000L, LocalDateTime.now().plusMinutes(10), "test-user-1");

        MvcResult result = mockMvc.perform(post("/bookings")
                        .header("X-User-Id", "test-user-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(checkoutId, "CARD", 150_000L)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SOLD_OUT_OR_PROCESSING"))
                .andExpect(jsonPath("$.retryable").value(true))
                .andExpect(jsonPath("$.retryAfterSeconds").isNumber())
                .andReturn();

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(findRecursively(body, "stock")).isFalse();
        assertThat(findRecursively(body, "qty")).isFalse();
        assertThat(findRecursively(body, "remaining")).isFalse();

        Integer bookingCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM booking WHERE checkout_id = ?", Integer.class, checkoutId);
        assertThat(bookingCount).isZero();
    }

    @Test
    @DisplayName("[완료조건] 카드 결제 거절 — 200 + FAILED, DB stock과 Redis gate 복구, Payment COMPENSATED")
    void cardDeclined_compensatesStockAndGate_andMarksFailed() throws Exception {
        long quotedPrice = FakePgClient.TRIGGER_CARD_DECLINED;
        String checkoutId = createCheckout(quotedPrice, LocalDateTime.now().plusMinutes(10), "test-user-1");

        mockMvc.perform(post("/bookings")
                        .header("X-User-Id", "test-user-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(checkoutId, "CARD", quotedPrice)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.checkoutId").value(checkoutId));

        Integer stockQty = jdbcTemplate.queryForObject(
                "SELECT qty FROM stock WHERE product_id = 1", Integer.class);
        assertThat(stockQty).isEqualTo(10);
        assertThat(redisTemplate.opsForValue().get("stock:1")).isEqualTo("10");
        assertThat(redisTemplate.hasKey("hold:" + checkoutId)).isFalse();

        String bookingStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM booking WHERE checkout_id = ?", String.class, checkoutId);
        assertThat(bookingStatus).isEqualTo("FAILED");

        String paymentStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM payment WHERE checkout_id = ?", String.class, checkoutId);
        assertThat(paymentStatus).isEqualTo("COMPENSATED");

        // Checkout은 USED로 변경 안 됨 (성공 시에만 USED)
        String checkoutStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM checkout WHERE checkout_id = ?", String.class, checkoutId);
        assertThat(checkoutStatus).isEqualTo("ISSUED");
    }

    private boolean findRecursively(JsonNode node, String fieldName) {
        if (node == null) return false;
        if (node.has(fieldName)) return true;
        for (JsonNode child : node) {
            if (findRecursively(child, fieldName)) return true;
        }
        return false;
    }
}
