package com.example.hellopani.payment.infra;

import java.time.LocalDateTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import com.example.hellopani.payment.domain.ChargeOutcome;
import com.example.hellopani.payment.domain.ChargeRequest;
import com.example.hellopani.payment.domain.FailureReason;
import com.example.hellopani.payment.domain.PaymentMethodType;
import com.example.hellopani.point.domain.PointReason;
import com.example.hellopani.point.infra.PointLedgerRepository;
import com.example.hellopani.point.infra.PointRepository;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class PointPaymentTest {

    @Autowired
    PointPayment pointPayment;

    @Autowired
    PointRepository pointRepository;

    @Autowired
    PointLedgerRepository pointLedgerRepository;

    @Autowired
    JdbcTemplate jdbcTemplate;

    String checkoutId;

    @BeforeEach
    void seed() {
        checkoutId = "ck-pp-" + System.nanoTime();
        jdbcTemplate.update(
                "INSERT INTO checkout "
                        + "(checkout_id, user_id, product_id, quoted_price, available_point_snapshot, status, expires_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                checkoutId, "test-user-1", 1L, 150000L, 50000L, "ISSUED",
                LocalDateTime.now().plusMinutes(10));
        jdbcTemplate.update("UPDATE point_account SET balance = 50000 WHERE user_id = 'test-user-1'");
    }

    @AfterEach
    void cleanup() {
        jdbcTemplate.update("DELETE FROM point_ledger WHERE checkout_id = ?", checkoutId);
        jdbcTemplate.update("DELETE FROM checkout WHERE checkout_id = ?", checkoutId);
        jdbcTemplate.update("UPDATE point_account SET balance = 50000 WHERE user_id = 'test-user-1'");
    }

    @Test
    void chargeDecrementsBalanceAndWritesLedger() {
        ChargeOutcome outcome = pointPayment.charge(req(20_000L));

        assertThat(outcome).isInstanceOf(ChargeOutcome.Succeeded.class);
        assertThat(pointRepository.findByUserId("test-user-1").orElseThrow().balance()).isEqualTo(30_000L);
        assertThat(pointLedgerRepository.findByCheckoutIdAndReason(checkoutId, PointReason.BOOKING_USE))
                .isPresent();
    }

    @Test
    void chargeFailsWhenBalanceInsufficient_andLedgerIsRolledBack() {
        ChargeOutcome outcome = pointPayment.charge(req(60_000L));

        ChargeOutcome.ConfirmedFailure failure = (ChargeOutcome.ConfirmedFailure) outcome;
        assertThat(failure.reason()).isEqualTo(FailureReason.INSUFFICIENT_POINT);
        assertThat(pointRepository.findByUserId("test-user-1").orElseThrow().balance()).isEqualTo(50_000L);
        assertThat(pointLedgerRepository.findByCheckoutIdAndReason(checkoutId, PointReason.BOOKING_USE))
                .isEmpty();
    }

    @Test
    void chargeIsIdempotentForSameCheckoutId() {
        pointPayment.charge(req(20_000L));
        ChargeOutcome second = pointPayment.charge(req(20_000L));

        assertThat(second).isInstanceOf(ChargeOutcome.Succeeded.class);
        assertThat(pointRepository.findByUserId("test-user-1").orElseThrow().balance()).isEqualTo(30_000L);
    }

    @Test
    void refundIncrementsBalanceAndWritesRefundLedger() {
        ChargeRequest request = req(20_000L);
        ChargeOutcome.Succeeded prior = (ChargeOutcome.Succeeded) pointPayment.charge(request);

        pointPayment.refund(request, prior);

        assertThat(pointRepository.findByUserId("test-user-1").orElseThrow().balance()).isEqualTo(50_000L);
        assertThat(pointLedgerRepository.findByCheckoutIdAndReason(checkoutId, PointReason.BOOKING_REFUND))
                .isPresent();
    }

    @Test
    void refundIsIdempotentForSameCheckoutId() {
        ChargeRequest request = req(20_000L);
        ChargeOutcome.Succeeded prior = (ChargeOutcome.Succeeded) pointPayment.charge(request);

        pointPayment.refund(request, prior);
        pointPayment.refund(request, prior);

        assertThat(pointRepository.findByUserId("test-user-1").orElseThrow().balance()).isEqualTo(50_000L);
    }

    private ChargeRequest req(long amount) {
        return new ChargeRequest(checkoutId, "test-user-1", PaymentMethodType.POINT, amount);
    }
}
