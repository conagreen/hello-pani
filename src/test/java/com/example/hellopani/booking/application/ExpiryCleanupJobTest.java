package com.example.hellopani.booking.application;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import com.example.hellopani.checkout.domain.Checkout;
import com.example.hellopani.checkout.infra.CheckoutRepository;
import com.example.hellopani.payment.domain.PaymentMethodType;
import com.example.hellopani.payment.domain.PaymentStatus;
import com.example.hellopani.payment.infra.PaymentComponentRepository;
import com.example.hellopani.payment.infra.PaymentRepository;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@DisplayName("ExpiryCleanupJob — 만료된 Checkout/Booking 정리 (불변식: SUCCEEDED·RESULT_PENDING은 건드리지 않음)")
class ExpiryCleanupJobTest {

    @Autowired
    ExpiryCleanupJob job;

    @Autowired
    CheckoutRepository checkoutRepository;

    @Autowired
    PaymentRepository paymentRepository;

    @Autowired
    PaymentComponentRepository componentRepository;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    StringRedisTemplate redisTemplate;

    @AfterEach
    void cleanupRows() {
        jdbcTemplate.update("DELETE FROM compensation_step WHERE checkout_id LIKE 'ck-exp-%'");
        jdbcTemplate.update("DELETE FROM payment_component WHERE payment_id IN "
                + "(SELECT payment_id FROM payment WHERE checkout_id LIKE 'ck-exp-%')");
        jdbcTemplate.update("DELETE FROM payment WHERE checkout_id LIKE 'ck-exp-%'");
        jdbcTemplate.update("DELETE FROM point_ledger WHERE checkout_id LIKE 'ck-exp-%'");
        jdbcTemplate.update("DELETE FROM booking WHERE checkout_id LIKE 'ck-exp-%'");
        jdbcTemplate.update("DELETE FROM checkout WHERE checkout_id LIKE 'ck-exp-%'");
        jdbcTemplate.update("UPDATE point_account SET balance = 50000 WHERE user_id = 'test-user-1'");
        jdbcTemplate.update("UPDATE stock SET qty = 10 WHERE product_id = 1");
        Set<String> holds = redisTemplate.keys("hold:*");
        if (holds != null && !holds.isEmpty()) redisTemplate.delete(holds);
        redisTemplate.opsForValue().set("stock:1", "10");
    }

    private String createExpiredCheckout(String userId) {
        String checkoutId = "ck-exp-" + System.nanoTime();
        jdbcTemplate.update(
                "INSERT INTO checkout "
                        + "(checkout_id, user_id, product_id, quoted_price, available_point_snapshot, status, expires_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                checkoutId, userId, 1L, 150000L, 50000L, "ISSUED",
                LocalDateTime.now().minusMinutes(1));
        return checkoutId;
    }

    private long createBooking(String checkoutId, String userId, String status) {
        KeyHolder bookingHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            var ps = connection.prepareStatement(
                    "INSERT INTO booking (checkout_id, user_id, product_id, status, total_amount) "
                            + "VALUES (?, ?, ?, ?, ?)",
                    java.sql.Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, checkoutId);
            ps.setString(2, userId);
            ps.setLong(3, 1L);
            ps.setString(4, status);
            ps.setLong(5, 150000L);
            return ps;
        }, bookingHolder);
        return Objects.requireNonNull(bookingHolder.getKey()).longValue();
    }

    @Test
    @DisplayName("만료된 Checkout + Booking 없음 → Checkout만 EXPIRED 마킹")
    void expiredCheckoutWithoutBooking_marksCheckoutExpired() {
        String checkoutId = createExpiredCheckout("test-user-1");

        Checkout pre = checkoutRepository.findById(checkoutId).orElseThrow();
        job.cleanupOne(pre);

        Checkout after = checkoutRepository.findById(checkoutId).orElseThrow();
        assertThat(after.status().name()).isEqualTo("EXPIRED");
    }

    @Test
    @DisplayName("[완료조건] 만료 + Booking PENDING_PAYMENT + Payment PROCESSING → 보상 + Booking FAILED + Checkout EXPIRED")
    void expiredWithStuckProcessing_compensatesAndMarksAllTerminal() {
        String checkoutId = createExpiredCheckout("test-user-1");
        long bookingId = createBooking(checkoutId, "test-user-1", "PENDING_PAYMENT");
        long paymentId = paymentRepository.insertProcessing(
                checkoutId, bookingId, "test-user-1", 150000L, checkoutId);
        long cardComponentId = componentRepository.insertPending(
                paymentId, PaymentMethodType.CARD, 150000L);

        // stock 9 (선점된 상태) + Redis hold
        jdbcTemplate.update("UPDATE stock SET qty = 9 WHERE product_id = 1");
        redisTemplate.opsForValue().set("stock:1", "9");
        redisTemplate.opsForHash().put("hold:" + checkoutId, "productId", "1");

        Checkout pre = checkoutRepository.findById(checkoutId).orElseThrow();
        job.cleanupOne(pre);

        Checkout after = checkoutRepository.findById(checkoutId).orElseThrow();
        assertThat(after.status().name()).isEqualTo("EXPIRED");

        String bookingStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM booking WHERE booking_id = ?", String.class, bookingId);
        assertThat(bookingStatus).isEqualTo("FAILED");

        var payment = paymentRepository.findById(paymentId).orElseThrow();
        assertThat(payment.status()).isEqualTo(PaymentStatus.COMPENSATED);

        Integer dbStock = jdbcTemplate.queryForObject(
                "SELECT qty FROM stock WHERE product_id = 1", Integer.class);
        assertThat(dbStock).isEqualTo(10);
        assertThat(redisTemplate.opsForValue().get("stock:1")).isEqualTo("10");
        assertThat(redisTemplate.hasKey("hold:" + checkoutId)).isFalse();
    }

    @Test
    @DisplayName("[완료조건] 만료 + Payment SUCCEEDED → 정리 잡은 절대 건드리지 않는다 (SUCCEEDED 보호 불변식)")
    void cleanupNeverTouchesSucceededPayment() {
        String checkoutId = createExpiredCheckout("test-user-1");
        long bookingId = createBooking(checkoutId, "test-user-1", "PENDING_PAYMENT");
        long paymentId = paymentRepository.insertProcessing(
                checkoutId, bookingId, "test-user-1", 150000L, checkoutId);
        // 비정상 sync: booking은 PENDING이지만 payment는 SUCCEEDED (이론상 sync 어긋남)
        paymentRepository.markCompleted(paymentId, PaymentStatus.SUCCEEDED, LocalDateTime.now());

        Checkout pre = checkoutRepository.findById(checkoutId).orElseThrow();
        job.cleanupOne(pre);

        var payment = paymentRepository.findById(paymentId).orElseThrow();
        assertThat(payment.status()).isEqualTo(PaymentStatus.SUCCEEDED);
        // booking과 checkout 상태는 변경되지 않음
        String bookingStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM booking WHERE booking_id = ?", String.class, bookingId);
        assertThat(bookingStatus).isEqualTo("PENDING_PAYMENT");
        Checkout after = checkoutRepository.findById(checkoutId).orElseThrow();
        assertThat(after.status().name()).isEqualTo("ISSUED");
    }

    @Test
    @DisplayName("만료 + Payment RESULT_PENDING → PaymentResolutionJob에 위임 (정리 잡이 건드리지 않음)")
    void cleanupDelegatesResultPendingToResolutionJob() {
        String checkoutId = createExpiredCheckout("test-user-1");
        long bookingId = createBooking(checkoutId, "test-user-1", "PENDING_PAYMENT");
        long paymentId = paymentRepository.insertProcessing(
                checkoutId, bookingId, "test-user-1", 150000L, checkoutId);
        paymentRepository.updateStatus(paymentId, PaymentStatus.RESULT_PENDING);

        Checkout pre = checkoutRepository.findById(checkoutId).orElseThrow();
        job.cleanupOne(pre);

        var payment = paymentRepository.findById(paymentId).orElseThrow();
        assertThat(payment.status()).isEqualTo(PaymentStatus.RESULT_PENDING);
        Checkout after = checkoutRepository.findById(checkoutId).orElseThrow();
        assertThat(after.status().name()).isEqualTo("ISSUED");
    }

    @Test
    @DisplayName("만료 + Booking FAILED → checkout만 EXPIRED 마킹 (이미 끝난 사이클)")
    void expiredWithFailedBooking_marksCheckoutExpiredOnly() {
        String checkoutId = createExpiredCheckout("test-user-1");
        long bookingId = createBooking(checkoutId, "test-user-1", "FAILED");

        Checkout pre = checkoutRepository.findById(checkoutId).orElseThrow();
        job.cleanupOne(pre);

        Checkout after = checkoutRepository.findById(checkoutId).orElseThrow();
        assertThat(after.status().name()).isEqualTo("EXPIRED");
        // booking은 FAILED 그대로
        String bookingStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM booking WHERE booking_id = ?", String.class, bookingId);
        assertThat(bookingStatus).isEqualTo("FAILED");
    }

    @Test
    @DisplayName("cleanupExpired는 만료된 Checkout 여러 건을 일괄 처리한다")
    void cleanupExpired_processesMultipleExpiredCheckouts() {
        createExpiredCheckout("test-user-1");
        createExpiredCheckout("test-user-1");

        job.cleanupExpired();

        Integer issuedExpiredRemaining = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM checkout WHERE checkout_id LIKE 'ck-exp-%' "
                        + "AND status = 'ISSUED' AND expires_at < CURRENT_TIMESTAMP(6)",
                Integer.class);
        assertThat(issuedExpiredRemaining).isZero();
    }
}
