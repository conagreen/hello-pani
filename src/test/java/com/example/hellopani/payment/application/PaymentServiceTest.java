package com.example.hellopani.payment.application;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import com.example.hellopani.payment.domain.AmountMismatchException;
import com.example.hellopani.payment.domain.FailureReason;
import com.example.hellopani.payment.domain.InvalidCompositionException;
import com.example.hellopani.payment.domain.Payment;
import com.example.hellopani.payment.domain.PaymentComponent;
import com.example.hellopani.payment.domain.PaymentComponentStatus;
import com.example.hellopani.payment.domain.PaymentMethodType;
import com.example.hellopani.payment.domain.PaymentStatus;
import com.example.hellopani.payment.infra.FakePgClient;
import com.example.hellopani.payment.infra.PaymentComponentRepository;
import com.example.hellopani.payment.infra.PaymentRepository;
import com.example.hellopani.point.infra.PointRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@DisplayName("PaymentService — 결제 도메인 통합 시나리오 (TASKS Task 5 완료 조건 7개)")
class PaymentServiceTest {

    @Autowired
    PaymentService paymentService;

    @Autowired
    PaymentRepository paymentRepository;

    @Autowired
    PaymentComponentRepository componentRepository;

    @Autowired
    PointRepository pointRepository;

    @Autowired
    FakePgClient fakePgClient;

    @Autowired
    JdbcTemplate jdbcTemplate;

    String checkoutId;
    long bookingId;

    @BeforeEach
    void seedFixture() {
        checkoutId = "ck-svc-" + System.nanoTime();
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

        jdbcTemplate.update("UPDATE point_account SET balance = 50000 WHERE user_id = 'test-user-1'");
        fakePgClient.reset();
    }

    @AfterEach
    void cleanup() {
        jdbcTemplate.update("DELETE FROM payment_component WHERE payment_id IN "
                + "(SELECT payment_id FROM payment WHERE checkout_id = ?)", checkoutId);
        jdbcTemplate.update("DELETE FROM payment WHERE checkout_id = ?", checkoutId);
        jdbcTemplate.update("DELETE FROM point_ledger WHERE checkout_id = ?", checkoutId);
        jdbcTemplate.update("DELETE FROM booking WHERE checkout_id = ?", checkoutId);
        jdbcTemplate.update("DELETE FROM checkout WHERE checkout_id = ?", checkoutId);
        jdbcTemplate.update("UPDATE point_account SET balance = 50000 WHERE user_id = 'test-user-1'");
    }

    @Test
    @DisplayName("[완료조건] 카드 단독 결제 — Payment SUCCEEDED, component SUCCEEDED + externalTxId 기록")
    void cardOnly_succeeds() {
        PaymentExecutionResult result = paymentService.execute(new PaymentExecutionContext(
                checkoutId, bookingId, "test-user-1", 150_000L,
                List.of(new PaymentExecutionContext.ComponentRequest(PaymentMethodType.CARD, 150_000L))));

        assertThat(result).isInstanceOf(PaymentExecutionResult.Succeeded.class);
        Payment payment = paymentRepository.findById(result.paymentId()).orElseThrow();
        assertThat(payment.status()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(payment.completedAt()).isNotNull();
        List<PaymentComponent> components = componentRepository.findByPaymentId(result.paymentId());
        assertThat(components).singleElement().satisfies(c -> {
            assertThat(c.method()).isEqualTo(PaymentMethodType.CARD);
            assertThat(c.status()).isEqualTo(PaymentComponentStatus.SUCCEEDED);
            assertThat(c.externalTransactionId()).startsWith("fake-pg-");
        });
    }

    @Test
    @DisplayName("[완료조건] 포인트 단독 결제 — 잔액 0으로 차감, Payment SUCCEEDED")
    void pointOnly_succeeds() {
        PaymentExecutionResult result = paymentService.execute(new PaymentExecutionContext(
                checkoutId, bookingId, "test-user-1", 50_000L,
                List.of(new PaymentExecutionContext.ComponentRequest(PaymentMethodType.POINT, 50_000L))));

        assertThat(result).isInstanceOf(PaymentExecutionResult.Succeeded.class);
        assertThat(pointRepository.findByUserId("test-user-1").orElseThrow().balance()).isZero();
        Payment payment = paymentRepository.findById(result.paymentId()).orElseThrow();
        assertThat(payment.status()).isEqualTo(PaymentStatus.SUCCEEDED);
    }

    @Test
    @DisplayName("[완료조건] 포인트 + 카드 복합 결제 — 두 component 모두 SUCCEEDED, Payment SUCCEEDED")
    void pointPlusCard_succeeds() {
        PaymentExecutionResult result = paymentService.execute(new PaymentExecutionContext(
                checkoutId, bookingId, "test-user-1", 150_000L,
                List.of(
                        new PaymentExecutionContext.ComponentRequest(PaymentMethodType.POINT, 50_000L),
                        new PaymentExecutionContext.ComponentRequest(PaymentMethodType.CARD, 100_000L)
                )));

        assertThat(result).isInstanceOf(PaymentExecutionResult.Succeeded.class);
        assertThat(pointRepository.findByUserId("test-user-1").orElseThrow().balance()).isZero();
        Payment payment = paymentRepository.findById(result.paymentId()).orElseThrow();
        assertThat(payment.status()).isEqualTo(PaymentStatus.SUCCEEDED);
        List<PaymentComponent> components = componentRepository.findByPaymentId(result.paymentId());
        assertThat(components).hasSize(2);
        assertThat(components).allSatisfy(c ->
                assertThat(c.status()).isEqualTo(PaymentComponentStatus.SUCCEEDED));
    }

    @Test
    @DisplayName("[완료조건] 카드 + Y페이 금지 — charge 시작 전 InvalidCompositionException")
    void cardPlusYPay_isRejectedByValidator() {
        PaymentExecutionContext ctx = new PaymentExecutionContext(
                checkoutId, bookingId, "test-user-1", 150_000L,
                List.of(
                        new PaymentExecutionContext.ComponentRequest(PaymentMethodType.CARD, 50_000L),
                        new PaymentExecutionContext.ComponentRequest(PaymentMethodType.Y_PAY, 100_000L)
                ));

        assertThatThrownBy(() -> paymentService.execute(ctx))
                .isInstanceOf(InvalidCompositionException.class);
    }

    @Test
    @DisplayName("component 합계 ≠ totalAmount는 AmountMismatchException으로 거절된다")
    void amountMismatch_isRejectedByValidator() {
        PaymentExecutionContext ctx = new PaymentExecutionContext(
                checkoutId, bookingId, "test-user-1", 150_000L,
                List.of(new PaymentExecutionContext.ComponentRequest(PaymentMethodType.CARD, 100_000L)));

        assertThatThrownBy(() -> paymentService.execute(ctx))
                .isInstanceOf(AmountMismatchException.class);
    }

    @Test
    @DisplayName("[완료조건] 포인트 차감 후 카드 거절 — 포인트 자동 복구, Payment COMPENSATED, 카드 component FAILED")
    void pointSucceededThenCardDeclined_refundsPoint_andMarksCompensated() {
        long cardAmount = FakePgClient.TRIGGER_CARD_DECLINED;
        long pointAmount = 50_000L;

        PaymentExecutionResult result = paymentService.execute(new PaymentExecutionContext(
                checkoutId, bookingId, "test-user-1", pointAmount + cardAmount,
                List.of(
                        new PaymentExecutionContext.ComponentRequest(PaymentMethodType.POINT, pointAmount),
                        new PaymentExecutionContext.ComponentRequest(PaymentMethodType.CARD, cardAmount)
                )));

        assertThat(result).isInstanceOf(PaymentExecutionResult.Failed.class);
        PaymentExecutionResult.Failed failed = (PaymentExecutionResult.Failed) result;
        assertThat(failed.reason()).isEqualTo(FailureReason.CARD_DECLINED);
        assertThat(failed.failedAt()).isEqualTo(PaymentMethodType.CARD);

        assertThat(pointRepository.findByUserId("test-user-1").orElseThrow().balance()).isEqualTo(50_000L);

        Payment payment = paymentRepository.findById(result.paymentId()).orElseThrow();
        assertThat(payment.status()).isEqualTo(PaymentStatus.COMPENSATED);

        List<PaymentComponent> components = componentRepository.findByPaymentId(result.paymentId());
        PaymentComponent point = components.stream()
                .filter(c -> c.method() == PaymentMethodType.POINT).findFirst().orElseThrow();
        PaymentComponent card = components.stream()
                .filter(c -> c.method() == PaymentMethodType.CARD).findFirst().orElseThrow();
        assertThat(point.status()).isEqualTo(PaymentComponentStatus.SUCCEEDED);
        assertThat(card.status()).isEqualTo(PaymentComponentStatus.FAILED);
    }

    @Test
    @DisplayName("[완료조건] PG 응답 미수신 — 즉시 보상하지 않고 Payment RESULT_PENDING, 포인트 차감 그대로 (Task 7 대기)")
    void cardPgPending_marksPaymentResultPending_withoutRefund() {
        long cardAmount = FakePgClient.TRIGGER_RESULT_PENDING;
        long pointAmount = 50_000L;

        PaymentExecutionResult result = paymentService.execute(new PaymentExecutionContext(
                checkoutId, bookingId, "test-user-1", pointAmount + cardAmount,
                List.of(
                        new PaymentExecutionContext.ComponentRequest(PaymentMethodType.POINT, pointAmount),
                        new PaymentExecutionContext.ComponentRequest(PaymentMethodType.CARD, cardAmount)
                )));

        assertThat(result).isInstanceOf(PaymentExecutionResult.Pending.class);
        PaymentExecutionResult.Pending pending = (PaymentExecutionResult.Pending) result;
        assertThat(pending.pendingAt()).isEqualTo(PaymentMethodType.CARD);
        assertThat(pending.pgIdempotencyKey()).isEqualTo(checkoutId);

        assertThat(pointRepository.findByUserId("test-user-1").orElseThrow().balance()).isZero();

        Payment payment = paymentRepository.findById(result.paymentId()).orElseThrow();
        assertThat(payment.status()).isEqualTo(PaymentStatus.RESULT_PENDING);
        assertThat(payment.completedAt()).isNull();

        List<PaymentComponent> components = componentRepository.findByPaymentId(result.paymentId());
        PaymentComponent point = components.stream()
                .filter(c -> c.method() == PaymentMethodType.POINT).findFirst().orElseThrow();
        PaymentComponent card = components.stream()
                .filter(c -> c.method() == PaymentMethodType.CARD).findFirst().orElseThrow();
        assertThat(point.status()).isEqualTo(PaymentComponentStatus.SUCCEEDED);
        assertThat(card.status()).isEqualTo(PaymentComponentStatus.PENDING);
    }
}
