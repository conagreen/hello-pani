package com.example.hellopani.payment.application;

import java.time.LocalDateTime;
import java.util.List;
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
import com.example.hellopani.compensation.domain.CompensationStep;
import com.example.hellopani.compensation.infra.CompensationStepRepository;
import com.example.hellopani.payment.domain.FailureReason;
import com.example.hellopani.payment.domain.Payment;
import com.example.hellopani.payment.domain.PaymentComponent;
import com.example.hellopani.payment.domain.PaymentComponentStatus;
import com.example.hellopani.payment.domain.PaymentMethodType;
import com.example.hellopani.payment.domain.PaymentStatus;
import com.example.hellopani.payment.domain.PgChargeResult;
import com.example.hellopani.payment.infra.FakePgClient;
import com.example.hellopani.payment.infra.PaymentComponentRepository;
import com.example.hellopani.payment.infra.PaymentRepository;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@DisplayName("PaymentResolutionJob — RESULT_PENDING 결과 조회 후 확정/보상")
class PaymentResolutionJobTest {

    @Autowired
    PaymentResolutionJob job;

    @Autowired
    PaymentRepository paymentRepository;

    @Autowired
    PaymentComponentRepository componentRepository;

    @Autowired
    CompensationStepRepository compensationStepRepository;

    @Autowired
    FakePgClient fakePgClient;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    StringRedisTemplate redisTemplate;

    String checkoutId;
    long bookingId;
    long paymentId;
    long pointComponentId;
    long cardComponentId;

    @BeforeEach
    void seedPendingPayment() {
        checkoutId = "ck-resolve-" + System.nanoTime();
        jdbcTemplate.update(
                "INSERT INTO checkout "
                        + "(checkout_id, user_id, product_id, quoted_price, available_point_snapshot, status, expires_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                checkoutId, "test-user-1", 1L, 150000L, 50000L, "ISSUED",
                LocalDateTime.now().plusMinutes(10));

        // booking PENDING_PAYMENT
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
        bookingId = Objects.requireNonNull(bookingHolder.getKey()).longValue();

        // payment RESULT_PENDING
        paymentId = paymentRepository.insertProcessing(
                checkoutId, bookingId, "test-user-1", 150000L, checkoutId);
        paymentRepository.updateStatus(paymentId, PaymentStatus.RESULT_PENDING);

        // POINT 50_000 SUCCEEDED + CARD 100_000 PENDING (composer가 RESULT_PENDING으로 끝낸 패턴)
        pointComponentId = componentRepository.insertPending(paymentId, PaymentMethodType.POINT, 50_000L);
        componentRepository.markSucceeded(pointComponentId, "internal-point-" + checkoutId);
        cardComponentId = componentRepository.insertPending(paymentId, PaymentMethodType.CARD, 100_000L);

        // POINT 차감 흔적: PointLedger BOOKING_USE + balance 감소 + Redis hold/stock 상태
        jdbcTemplate.update(
                "INSERT INTO point_ledger (user_id, checkout_id, amount, reason) VALUES (?, ?, ?, ?)",
                "test-user-1", checkoutId, -50_000L, "BOOKING_USE");
        jdbcTemplate.update("UPDATE point_account SET balance = 0 WHERE user_id = 'test-user-1'");
        jdbcTemplate.update("UPDATE stock SET qty = 9 WHERE product_id = 1");
        redisTemplate.opsForValue().set("stock:1", "9");
        redisTemplate.opsForHash().put("hold:" + checkoutId, "productId", "1");
        redisTemplate.opsForHash().put("hold:" + checkoutId, "userId", "test-user-1");
        redisTemplate.expire("hold:" + checkoutId, java.time.Duration.ofMinutes(30));

        fakePgClient.reset();
    }

    @AfterEach
    void cleanup() {
        jdbcTemplate.update("DELETE FROM compensation_step WHERE checkout_id = ?", checkoutId);
        jdbcTemplate.update("DELETE FROM payment_component WHERE payment_id = ?", paymentId);
        jdbcTemplate.update("DELETE FROM payment WHERE checkout_id = ?", checkoutId);
        jdbcTemplate.update("DELETE FROM point_ledger WHERE checkout_id = ?", checkoutId);
        jdbcTemplate.update("DELETE FROM booking WHERE checkout_id = ?", checkoutId);
        jdbcTemplate.update("DELETE FROM checkout WHERE checkout_id = ?", checkoutId);
        jdbcTemplate.update("UPDATE point_account SET balance = 50000 WHERE user_id = 'test-user-1'");
        jdbcTemplate.update("UPDATE stock SET qty = 10 WHERE product_id = 1");
        Set<String> holds = redisTemplate.keys("hold:*");
        if (holds != null && !holds.isEmpty()) redisTemplate.delete(holds);
        redisTemplate.opsForValue().set("stock:1", "10");
    }

    @Test
    @DisplayName("[완료조건] PG 결과 조회 = Approved → Payment SUCCEEDED + Booking CONFIRMED + Checkout USED")
    void resolveApproved_marksSucceededAndConfirmsBooking() {
        fakePgClient.primeResult(checkoutId, new PgChargeResult.Approved("pg-tx-late-1"));

        Payment pending = paymentRepository.findById(paymentId).orElseThrow();
        job.resolveOne(pending);

        Payment after = paymentRepository.findById(paymentId).orElseThrow();
        assertThat(after.status()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(after.completedAt()).isNotNull();

        List<PaymentComponent> components = componentRepository.findByPaymentId(paymentId);
        PaymentComponent card = components.stream()
                .filter(c -> c.method() == PaymentMethodType.CARD).findFirst().orElseThrow();
        assertThat(card.status()).isEqualTo(PaymentComponentStatus.SUCCEEDED);
        assertThat(card.externalTransactionId()).isEqualTo("pg-tx-late-1");

        String bookingStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM booking WHERE booking_id = ?", String.class, bookingId);
        assertThat(bookingStatus).isEqualTo("CONFIRMED");

        String checkoutStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM checkout WHERE checkout_id = ?", String.class, checkoutId);
        assertThat(checkoutStatus).isEqualTo("USED");
    }

    @Test
    @DisplayName("[완료조건] PG 결과 조회 = Declined → Payment COMPENSATED + Booking FAILED + 보상 (POINT 복구, Stock 복구, Redis gate 복구)")
    void resolveDeclined_compensatesAndMarksFailed() {
        fakePgClient.primeResult(checkoutId, new PgChargeResult.Declined(FailureReason.CARD_DECLINED));

        Payment pending = paymentRepository.findById(paymentId).orElseThrow();
        job.resolveOne(pending);

        Payment after = paymentRepository.findById(paymentId).orElseThrow();
        assertThat(after.status()).isEqualTo(PaymentStatus.COMPENSATED);

        List<PaymentComponent> components = componentRepository.findByPaymentId(paymentId);
        PaymentComponent card = components.stream()
                .filter(c -> c.method() == PaymentMethodType.CARD).findFirst().orElseThrow();
        assertThat(card.status()).isEqualTo(PaymentComponentStatus.FAILED);

        String bookingStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM booking WHERE booking_id = ?", String.class, bookingId);
        assertThat(bookingStatus).isEqualTo("FAILED");

        // 보상 단계 모두 기록됨
        assertThat(compensationStepRepository.isCompleted(checkoutId, CompensationStep.POINT_REFUNDED)).isTrue();
        assertThat(compensationStepRepository.isCompleted(checkoutId, CompensationStep.DB_STOCK_RESTORED)).isTrue();
        assertThat(compensationStepRepository.isCompleted(checkoutId, CompensationStep.REDIS_GATE_RESTORED)).isTrue();

        // POINT balance 복구
        Long balance = jdbcTemplate.queryForObject(
                "SELECT balance FROM point_account WHERE user_id = 'test-user-1'", Long.class);
        assertThat(balance).isEqualTo(50_000L);

        // DB stock + Redis stock 복구
        Integer dbStock = jdbcTemplate.queryForObject(
                "SELECT qty FROM stock WHERE product_id = 1", Integer.class);
        assertThat(dbStock).isEqualTo(10);
        assertThat(redisTemplate.opsForValue().get("stock:1")).isEqualTo("10");
        assertThat(redisTemplate.hasKey("hold:" + checkoutId)).isFalse();
    }

    @Test
    @DisplayName("PG 결과 조회 = Pending → 보상하지 않고 hold TTL만 연장 (Payment RESULT_PENDING 유지)")
    void resolveStillPending_extendsHoldTtl_withoutCompensating() {
        fakePgClient.primeResult(checkoutId, new PgChargeResult.Pending(checkoutId));

        Payment pending = paymentRepository.findById(paymentId).orElseThrow();
        job.resolveOne(pending);

        Payment after = paymentRepository.findById(paymentId).orElseThrow();
        assertThat(after.status()).isEqualTo(PaymentStatus.RESULT_PENDING);

        // hold 살아있고 TTL 연장됨
        assertThat(redisTemplate.hasKey("hold:" + checkoutId)).isTrue();
        Long ttl = redisTemplate.getExpire("hold:" + checkoutId);
        assertThat(ttl).isPositive();

        // 보상 단계 미기록
        assertThat(compensationStepRepository.isCompleted(checkoutId, CompensationStep.DB_STOCK_RESTORED)).isFalse();
    }

    @Test
    @DisplayName("PG lookupResult = NotFound (결과 미수신) → 보상하지 않고 hold TTL 연장만")
    void resolveNotFound_extendsHoldTtl_withoutCompensating() {
        // FakePgClient.reset() 이후 lookup 시 NotFound 반환 (BeforeEach에서 reset 호출됨)

        Payment pending = paymentRepository.findById(paymentId).orElseThrow();
        job.resolveOne(pending);

        Payment after = paymentRepository.findById(paymentId).orElseThrow();
        assertThat(after.status()).isEqualTo(PaymentStatus.RESULT_PENDING);

        assertThat(redisTemplate.hasKey("hold:" + checkoutId)).isTrue();
        assertThat(compensationStepRepository.isCompleted(checkoutId, CompensationStep.DB_STOCK_RESTORED)).isFalse();
    }

    @Test
    @DisplayName("resolveAllPending이 여러 RESULT_PENDING을 일괄 처리한다")
    void resolveAllPending_processesMultipleRows() {
        fakePgClient.primeResult(checkoutId, new PgChargeResult.Approved("pg-tx-batch"));

        job.resolveAllPending();

        Payment after = paymentRepository.findById(paymentId).orElseThrow();
        assertThat(after.status()).isEqualTo(PaymentStatus.SUCCEEDED);
    }
}
