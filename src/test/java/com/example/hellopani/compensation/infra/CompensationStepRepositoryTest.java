package com.example.hellopani.compensation.infra;

import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import com.example.hellopani.compensation.domain.CompensationStep;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(CompensationStepRepository.class)
@DisplayName("CompensationStepRepository — 보상 단계 완료 기록 (checkout_id, step) UNIQUE")
class CompensationStepRepositoryTest {

    @Autowired
    CompensationStepRepository repository;

    @Autowired
    JdbcTemplate jdbcTemplate;

    String checkoutId;

    @BeforeEach
    void seedCheckout() {
        checkoutId = "ck-cs-" + System.nanoTime();
        jdbcTemplate.update(
                "INSERT INTO checkout "
                        + "(checkout_id, user_id, product_id, quoted_price, available_point_snapshot, status, expires_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                checkoutId, "test-user-1", 1L, 150000L, 50000L, "ISSUED",
                LocalDateTime.now().plusMinutes(10));
    }

    @Test
    @DisplayName("기록되지 않은 단계는 isCompleted=false를 반환한다")
    void unrecordedStepReturnsFalse() {
        assertThat(repository.isCompleted(checkoutId, CompensationStep.POINT_REFUNDED)).isFalse();
    }

    @Test
    @DisplayName("INSERT 후 isCompleted=true로 바뀌고 같은 단계 INSERT는 DuplicateKeyException을 던진다")
    void insertedStepIsCompleted_andDuplicateInsertThrows() {
        repository.insert(checkoutId, CompensationStep.DB_STOCK_RESTORED);

        assertThat(repository.isCompleted(checkoutId, CompensationStep.DB_STOCK_RESTORED)).isTrue();

        assertThatThrownBy(() -> repository.insert(checkoutId, CompensationStep.DB_STOCK_RESTORED))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    @DisplayName("같은 checkoutId의 다른 단계는 독립적으로 기록될 수 있다")
    void differentStepsOnSameCheckoutAreIndependent() {
        repository.insert(checkoutId, CompensationStep.POINT_REFUNDED);
        repository.insert(checkoutId, CompensationStep.DB_STOCK_RESTORED);

        assertThat(repository.isCompleted(checkoutId, CompensationStep.POINT_REFUNDED)).isTrue();
        assertThat(repository.isCompleted(checkoutId, CompensationStep.DB_STOCK_RESTORED)).isTrue();
        assertThat(repository.isCompleted(checkoutId, CompensationStep.REDIS_GATE_RESTORED)).isFalse();
    }
}
