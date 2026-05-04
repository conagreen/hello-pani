package com.example.hellopani.compensation.application;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import com.example.hellopani.inventory.domain.RedisUnavailableException;
import com.example.hellopani.inventory.domain.StockGate;
import com.example.hellopani.payment.domain.PaymentStatus;
import com.example.hellopani.payment.infra.PaymentRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;

@SpringBootTest
@DisplayName("CompensationService — 보상 단계 실패 시 Payment REFUND_FAILED 마킹 + Micrometer counter 증가")
class RefundFailedMarkingTest {

    @Autowired
    CompensationService compensationService;

    @Autowired
    PaymentRepository paymentRepository;

    @Autowired
    MeterRegistry meterRegistry;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    StringRedisTemplate redisTemplate;

    @MockitoBean
    StockGate stockGate;

    String checkoutId;
    long paymentId;

    @BeforeEach
    void seed() {
        checkoutId = "ck-rf-" + System.nanoTime();
        jdbcTemplate.update(
                "INSERT INTO checkout "
                        + "(checkout_id, user_id, product_id, quoted_price, available_point_snapshot, status, expires_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                checkoutId, "test-user-1", 1L, 150000L, 50000L, "ISSUED",
                LocalDateTime.now().plusMinutes(10));

        KeyHolder bookingHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            var ps = connection.prepareStatement(
                    "INSERT INTO booking (checkout_id, user_id, product_id, status, total_amount) "
                            + "VALUES (?, ?, ?, ?, ?)",
                    java.sql.Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, checkoutId);
            ps.setString(2, "test-user-1");
            ps.setLong(3, 1L);
            ps.setString(4, "PENDING_PAYMENT");
            ps.setLong(5, 150000L);
            return ps;
        }, bookingHolder);
        long bookingId = Objects.requireNonNull(bookingHolder.getKey()).longValue();

        paymentId = paymentRepository.insertProcessing(
                checkoutId, bookingId, "test-user-1", 150000L, checkoutId);
    }

    @AfterEach
    void cleanup() {
        jdbcTemplate.update("DELETE FROM compensation_step WHERE checkout_id = ?", checkoutId);
        jdbcTemplate.update("DELETE FROM payment WHERE checkout_id = ?", checkoutId);
        jdbcTemplate.update("DELETE FROM booking WHERE checkout_id = ?", checkoutId);
        jdbcTemplate.update("DELETE FROM checkout WHERE checkout_id = ?", checkoutId);
        jdbcTemplate.update("UPDATE point_account SET balance = 50000 WHERE user_id = 'test-user-1'");
        jdbcTemplate.update("UPDATE stock SET qty = 10 WHERE product_id = 1");
        Set<String> holds = redisTemplate.keys("hold:*");
        if (holds != null && !holds.isEmpty()) redisTemplate.delete(holds);
        redisTemplate.opsForValue().set("stock:1", "10");
    }

    @Test
    @DisplayName("Redis gate release가 throw하면 Payment.status = REFUND_FAILED로 마킹되고 counter가 증가한다")
    void redisFailureDuringCompensation_marksRefundFailed_andIncrementsCounter() {
        doThrow(new RedisUnavailableException("simulated outage", null))
                .when(stockGate).release(anyLong(), anyString());

        double before = meterRegistry.counter("compensation.refund_failed").count();

        CompensationContext ctx = new CompensationContext(
                checkoutId, "test-user-1", 1L, 0L, paymentId);

        assertThatThrownBy(() -> compensationService.compensate(ctx))
                .isInstanceOf(RedisUnavailableException.class);

        var payment = paymentRepository.findById(paymentId).orElseThrow();
        assertThat(payment.status()).isEqualTo(PaymentStatus.REFUND_FAILED);

        double after = meterRegistry.counter("compensation.refund_failed").count();
        assertThat(after - before).isEqualTo(1.0);
    }
}
