package com.example.hellopani.checkout.infra;

import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import com.example.hellopani.checkout.domain.Checkout;
import com.example.hellopani.checkout.domain.CheckoutStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(CheckoutRepository.class)
class CheckoutRepositoryTest {

    @Autowired
    CheckoutRepository checkoutRepository;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    void insertsCheckoutRowWithIssuedStatus() {
        LocalDateTime now = LocalDateTime.now();
        Checkout checkout = new Checkout(
                "ck-test-1", "test-user-1", 1L, 150000L, 50000L,
                CheckoutStatus.ISSUED, now.plusMinutes(10), now);

        checkoutRepository.insert(checkout);

        Integer rows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM checkout WHERE checkout_id = 'ck-test-1'", Integer.class);
        assertThat(rows).isEqualTo(1);

        String status = jdbcTemplate.queryForObject(
                "SELECT status FROM checkout WHERE checkout_id = 'ck-test-1'", String.class);
        assertThat(status).isEqualTo("ISSUED");

        Long quotedPrice = jdbcTemplate.queryForObject(
                "SELECT quoted_price FROM checkout WHERE checkout_id = 'ck-test-1'", Long.class);
        assertThat(quotedPrice).isEqualTo(150000L);

        Long pointSnapshot = jdbcTemplate.queryForObject(
                "SELECT available_point_snapshot FROM checkout WHERE checkout_id = 'ck-test-1'", Long.class);
        assertThat(pointSnapshot).isEqualTo(50000L);
    }

    @Test
    void rejectsDuplicateCheckoutId() {
        LocalDateTime now = LocalDateTime.now();
        Checkout first = new Checkout(
                "ck-dup", "test-user-1", 1L, 150000L, 50000L,
                CheckoutStatus.ISSUED, now.plusMinutes(10), now);
        checkoutRepository.insert(first);

        Checkout duplicate = new Checkout(
                "ck-dup", "test-user-1", 1L, 150000L, 50000L,
                CheckoutStatus.ISSUED, now.plusMinutes(10), now);

        assertThatThrownBy(() -> checkoutRepository.insert(duplicate))
                .isInstanceOf(DuplicateKeyException.class);
    }
}
