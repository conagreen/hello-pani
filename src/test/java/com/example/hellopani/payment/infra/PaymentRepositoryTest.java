package com.example.hellopani.payment.infra;

import java.time.LocalDateTime;
import java.util.Objects;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import com.example.hellopani.payment.domain.Payment;
import com.example.hellopani.payment.domain.PaymentStatus;

import static org.assertj.core.api.Assertions.assertThat;

@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(PaymentRepository.class)
class PaymentRepositoryTest {

    @Autowired
    PaymentRepository paymentRepository;

    @Autowired
    JdbcTemplate jdbcTemplate;

    String checkoutId;
    long bookingId;

    @BeforeEach
    void seedCheckoutAndBooking() {
        checkoutId = "ck-pay-" + System.nanoTime();
        jdbcTemplate.update(
                "INSERT INTO checkout "
                        + "(checkout_id, user_id, product_id, quoted_price, available_point_snapshot, status, expires_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                checkoutId, "test-user-1", 1L, 150000L, 50000L, "ISSUED",
                LocalDateTime.now().plusMinutes(10));

        KeyHolder keyHolder = new GeneratedKeyHolder();
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
        }, keyHolder);
        bookingId = Objects.requireNonNull(keyHolder.getKey()).longValue();
    }

    @Test
    void insertsProcessingPaymentAndReturnsGeneratedId() {
        long paymentId = paymentRepository.insertProcessing(
                checkoutId, bookingId, "test-user-1", 150000L, checkoutId);

        Payment payment = paymentRepository.findById(paymentId).orElseThrow();
        assertThat(payment.status()).isEqualTo(PaymentStatus.PROCESSING);
        assertThat(payment.totalAmount()).isEqualTo(150000L);
        assertThat(payment.checkoutId()).isEqualTo(checkoutId);
        assertThat(payment.bookingId()).isEqualTo(bookingId);
        assertThat(payment.pgIdempotencyKey()).isEqualTo(checkoutId);
        assertThat(payment.completedAt()).isNull();
    }

    @Test
    void updatesStatusWithoutTouchingCompletedAt() {
        long paymentId = paymentRepository.insertProcessing(
                checkoutId, bookingId, "test-user-1", 150000L, checkoutId);

        int affected = paymentRepository.updateStatus(paymentId, PaymentStatus.RESULT_PENDING);

        assertThat(affected).isEqualTo(1);
        Payment payment = paymentRepository.findById(paymentId).orElseThrow();
        assertThat(payment.status()).isEqualTo(PaymentStatus.RESULT_PENDING);
        assertThat(payment.completedAt()).isNull();
    }

    @Test
    void marksCompletedRecordsTimestamp() {
        long paymentId = paymentRepository.insertProcessing(
                checkoutId, bookingId, "test-user-1", 150000L, checkoutId);

        LocalDateTime now = LocalDateTime.now();
        int affected = paymentRepository.markCompleted(paymentId, PaymentStatus.SUCCEEDED, now);

        assertThat(affected).isEqualTo(1);
        Payment payment = paymentRepository.findById(paymentId).orElseThrow();
        assertThat(payment.status()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(payment.completedAt()).isNotNull();
    }

    @Test
    void findsByCheckoutId() {
        long paymentId = paymentRepository.insertProcessing(
                checkoutId, bookingId, "test-user-1", 150000L, checkoutId);

        Payment payment = paymentRepository.findByCheckoutId(checkoutId).orElseThrow();
        assertThat(payment.paymentId()).isEqualTo(paymentId);
    }

    @Test
    void returnsEmptyForUnknownPaymentId() {
        assertThat(paymentRepository.findById(999_999L)).isEmpty();
    }
}
