package com.example.hellopani.payment.infra;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import com.example.hellopani.payment.domain.PaymentComponent;
import com.example.hellopani.payment.domain.PaymentComponentStatus;
import com.example.hellopani.payment.domain.PaymentMethodType;

import static org.assertj.core.api.Assertions.assertThat;

@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({PaymentRepository.class, PaymentComponentRepository.class})
@DisplayName("PaymentComponentRepository — 결제 단위 영속화와 상태 전이")
class PaymentComponentRepositoryTest {

    @Autowired
    PaymentRepository paymentRepository;

    @Autowired
    PaymentComponentRepository componentRepository;

    @Autowired
    JdbcTemplate jdbcTemplate;

    String checkoutId;
    long paymentId;

    @BeforeEach
    void seed() {
        checkoutId = "ck-pc-" + System.nanoTime();
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
        long bookingId = Objects.requireNonNull(keyHolder.getKey()).longValue();

        paymentId = paymentRepository.insertProcessing(
                checkoutId, bookingId, "test-user-1", 150000L, checkoutId);
    }

    @Test
    @DisplayName("insertPending은 PaymentComponent를 PENDING으로 영속화한다 (externalTransactionId는 null)")
    void insertsPendingComponentAndReturnsId() {
        long componentId = componentRepository.insertPending(
                paymentId, PaymentMethodType.POINT, 50000L);

        List<PaymentComponent> components = componentRepository.findByPaymentId(paymentId);
        assertThat(components).hasSize(1);
        PaymentComponent component = components.get(0);
        assertThat(component.paymentComponentId()).isEqualTo(componentId);
        assertThat(component.method()).isEqualTo(PaymentMethodType.POINT);
        assertThat(component.amount()).isEqualTo(50000L);
        assertThat(component.status()).isEqualTo(PaymentComponentStatus.PENDING);
        assertThat(component.externalTransactionId()).isNull();
    }

    @Test
    @DisplayName("markSucceeded는 component status를 SUCCEEDED로 바꾸고 외부 거래번호를 기록한다")
    void marksComponentSucceededWithExternalTxId() {
        long componentId = componentRepository.insertPending(
                paymentId, PaymentMethodType.CARD, 100000L);

        int affected = componentRepository.markSucceeded(componentId, "pg-tx-abc");

        assertThat(affected).isEqualTo(1);
        PaymentComponent component = componentRepository.findByPaymentId(paymentId).get(0);
        assertThat(component.status()).isEqualTo(PaymentComponentStatus.SUCCEEDED);
        assertThat(component.externalTransactionId()).isEqualTo("pg-tx-abc");
    }

    @Test
    @DisplayName("markFailed는 component status를 FAILED로 바꾼다 (확정 실패 component)")
    void marksComponentFailed() {
        long componentId = componentRepository.insertPending(
                paymentId, PaymentMethodType.CARD, 100000L);

        int affected = componentRepository.markFailed(componentId);

        assertThat(affected).isEqualTo(1);
        PaymentComponent component = componentRepository.findByPaymentId(paymentId).get(0);
        assertThat(component.status()).isEqualTo(PaymentComponentStatus.FAILED);
    }

    @Test
    @DisplayName("findByPaymentId는 INSERT 순서대로 component를 반환한다 (Composer 실행 순서와 일치)")
    void preservesInsertOrder() {
        componentRepository.insertPending(paymentId, PaymentMethodType.POINT, 50000L);
        componentRepository.insertPending(paymentId, PaymentMethodType.CARD, 100000L);

        List<PaymentComponent> components = componentRepository.findByPaymentId(paymentId);
        assertThat(components).hasSize(2);
        assertThat(components.get(0).method()).isEqualTo(PaymentMethodType.POINT);
        assertThat(components.get(1).method()).isEqualTo(PaymentMethodType.CARD);
    }
}
