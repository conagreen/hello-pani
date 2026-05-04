package com.example.hellopani.point.infra;

import java.util.Optional;
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
class PointRepositoryTest {

    @Autowired
    PointRepository pointRepository;

    @Test
    void findsExistingSeedAccount() {
        Optional<PointAccount> result = pointRepository.findByUserId("test-user-1");

        assertThat(result).isPresent();
        PointAccount account = result.get();
        assertThat(account.userId()).isEqualTo("test-user-1");
        assertThat(account.balance()).isEqualTo(50000L);
        assertThat(account.updatedAt()).isNotNull();
    }

    @Test
    void returnsEmptyWhenUserMissing() {
        assertThat(pointRepository.findByUserId("brand-new-user")).isEmpty();
    }

    @Test
    void decrementsWhenBalanceSufficient() {
        int affected = pointRepository.decrement("test-user-1", 30000L);

        assertThat(affected).isEqualTo(1);
        assertThat(pointRepository.findByUserId("test-user-1").orElseThrow().balance()).isEqualTo(20000L);
    }

    @Test
    void decrementBlocksWhenBalanceInsufficient() {
        int affected = pointRepository.decrement("test-user-1", 60000L);

        assertThat(affected).isEqualTo(0);
        assertThat(pointRepository.findByUserId("test-user-1").orElseThrow().balance()).isEqualTo(50000L);
    }

    @Test
    void decrementBlocksAtExactBoundary() {
        assertThat(pointRepository.decrement("test-user-1", 50000L)).isEqualTo(1);
        assertThat(pointRepository.findByUserId("test-user-1").orElseThrow().balance()).isZero();
        assertThat(pointRepository.decrement("test-user-1", 1L)).isEqualTo(0);
    }

    @Test
    void incrementIncreasesBalance() {
        pointRepository.decrement("test-user-1", 20000L);
        int affected = pointRepository.increment("test-user-1", 5000L);

        assertThat(affected).isEqualTo(1);
        assertThat(pointRepository.findByUserId("test-user-1").orElseThrow().balance()).isEqualTo(35000L);
    }

    @Test
    void incrementReturnsZeroForUnknownUser() {
        int affected = pointRepository.increment("brand-new-user", 1000L);

        assertThat(affected).isEqualTo(0);
    }
}
