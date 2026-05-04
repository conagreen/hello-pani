package com.example.hellopani.point.infra;

import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.context.annotation.Import;
import com.example.hellopani.point.domain.PointAccount;

import static org.assertj.core.api.Assertions.assertThat;

@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(PointRepository.class)
@DisplayName("PointRepository — 포인트 조회 / 차감 / 복구")
class PointRepositoryTest {

    @Autowired
    PointRepository pointRepository;

    @Test
    @DisplayName("seed 사용자의 포인트 계좌를 조회한다")
    void findsExistingSeedAccount() {
        Optional<PointAccount> result = pointRepository.findByUserId("test-user-1");

        assertThat(result).isPresent();
        PointAccount account = result.get();
        assertThat(account.userId()).isEqualTo("test-user-1");
        assertThat(account.balance()).isEqualTo(50000L);
        assertThat(account.updatedAt()).isNotNull();
    }

    @Test
    @DisplayName("존재하지 않는 userId는 빈 Optional을 반환한다")
    void returnsEmptyWhenUserMissing() {
        assertThat(pointRepository.findByUserId("brand-new-user")).isEmpty();
    }

    @Test
    @DisplayName("잔액이 충분하면 조건부 UPDATE로 차감된다")
    void decrementsWhenBalanceSufficient() {
        int affected = pointRepository.decrement("test-user-1", 30000L);

        assertThat(affected).isEqualTo(1);
        assertThat(pointRepository.findByUserId("test-user-1").orElseThrow().balance()).isEqualTo(20000L);
    }

    @Test
    @DisplayName("잔액이 부족하면 영향 row 0으로 차감을 거부하고 잔액을 유지한다")
    void decrementBlocksWhenBalanceInsufficient() {
        int affected = pointRepository.decrement("test-user-1", 60000L);

        assertThat(affected).isEqualTo(0);
        assertThat(pointRepository.findByUserId("test-user-1").orElseThrow().balance()).isEqualTo(50000L);
    }

    @Test
    @DisplayName("잔액과 정확히 같은 금액 차감은 성공하고 그 다음 1원 차감은 거부된다")
    void decrementBlocksAtExactBoundary() {
        assertThat(pointRepository.decrement("test-user-1", 50000L)).isEqualTo(1);
        assertThat(pointRepository.findByUserId("test-user-1").orElseThrow().balance()).isZero();
        assertThat(pointRepository.decrement("test-user-1", 1L)).isEqualTo(0);
    }

    @Test
    @DisplayName("increment는 잔액을 양수만큼 증가시킨다")
    void incrementIncreasesBalance() {
        pointRepository.decrement("test-user-1", 20000L);
        int affected = pointRepository.increment("test-user-1", 5000L);

        assertThat(affected).isEqualTo(1);
        assertThat(pointRepository.findByUserId("test-user-1").orElseThrow().balance()).isEqualTo(35000L);
    }

    @Test
    @DisplayName("존재하지 않는 사용자에 대한 increment는 영향 row 0을 반환한다")
    void incrementReturnsZeroForUnknownUser() {
        int affected = pointRepository.increment("brand-new-user", 1000L);

        assertThat(affected).isEqualTo(0);
    }
}
