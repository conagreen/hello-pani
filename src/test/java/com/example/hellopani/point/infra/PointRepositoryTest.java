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
}
