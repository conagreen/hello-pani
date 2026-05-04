package com.example.hellopani.payment.infra;

import java.time.LocalDateTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
@DisplayName("PointPayment — 잔액 차감 / 보상 / ledger 멱등 처리")
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
    @DisplayName("정상 charge는 ledger(BOOKING_USE) 기록과 함께 잔액을 차감한다")
    void chargeDecrementsBalanceAndWritesLedger() {
        ChargeOutcome outcome = pointPayment.charge(req(20_000L));

        assertThat(outcome).isInstanceOf(ChargeOutcome.Succeeded.class);
        assertThat(pointRepository.findByUserId("test-user-1").orElseThrow().balance()).isEqualTo(30_000L);
        assertThat(pointLedgerRepository.findByCheckoutIdAndReason(checkoutId, PointReason.BOOKING_USE))
                .isPresent();
    }

    @Test
    @DisplayName("잔액 부족 시 INSUFFICIENT_POINT를 반환하고 ledger insert까지 함께 롤백된다")
    void chargeFailsWhenBalanceInsufficient_andLedgerIsRolledBack() {
        ChargeOutcome outcome = pointPayment.charge(req(60_000L));

        ChargeOutcome.ConfirmedFailure failure = (ChargeOutcome.ConfirmedFailure) outcome;
        assertThat(failure.reason()).isEqualTo(FailureReason.INSUFFICIENT_POINT);
        assertThat(pointRepository.findByUserId("test-user-1").orElseThrow().balance()).isEqualTo(50_000L);
        assertThat(pointLedgerRepository.findByCheckoutIdAndReason(checkoutId, PointReason.BOOKING_USE))
                .isEmpty();
    }

    @Test
    @DisplayName("같은 checkoutId 재시도 시 ledger UNIQUE로 멱등 처리되어 잔액이 두 번 차감되지 않는다")
    void chargeIsIdempotentForSameCheckoutId() {
        pointPayment.charge(req(20_000L));
        ChargeOutcome second = pointPayment.charge(req(20_000L));

        assertThat(second).isInstanceOf(ChargeOutcome.Succeeded.class);
        assertThat(pointRepository.findByUserId("test-user-1").orElseThrow().balance()).isEqualTo(30_000L);
    }

    @Test
    @DisplayName("refund는 ledger(BOOKING_REFUND) 기록과 함께 잔액을 복구한다")
    void refundIncrementsBalanceAndWritesRefundLedger() {
        ChargeRequest request = req(20_000L);
        ChargeOutcome.Succeeded prior = (ChargeOutcome.Succeeded) pointPayment.charge(request);

        pointPayment.refund(request, prior);

        assertThat(pointRepository.findByUserId("test-user-1").orElseThrow().balance()).isEqualTo(50_000L);
        assertThat(pointLedgerRepository.findByCheckoutIdAndReason(checkoutId, PointReason.BOOKING_REFUND))
                .isPresent();
    }

    @Test
    @DisplayName("같은 checkoutId로 refund를 두 번 호출해도 잔액이 중복 복구되지 않는다 (보상 멱등)")
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
