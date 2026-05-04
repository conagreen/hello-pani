package com.example.hellopani.point.infra;

import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import com.example.hellopani.point.domain.PointLedger;
import com.example.hellopani.point.domain.PointReason;

import static org.assertj.core.api.Assertions.assertThat;

@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(PointLedgerRepository.class)
class PointLedgerRepositoryTest {

    @Autowired
    PointLedgerRepository pointLedgerRepository;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @BeforeEach
    void seedCheckout() {
        jdbcTemplate.update(
                "INSERT INTO checkout "
                        + "(checkout_id, user_id, product_id, quoted_price, available_point_snapshot, status, expires_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                "ck-ledger-1", "test-user-1", 1L, 150000L, 50000L, "ISSUED",
                LocalDateTime.now().plusMinutes(10));
    }

    @Test
    void firstInsertReturnsTrue() {
        boolean inserted = pointLedgerRepository.tryInsert(
                "test-user-1", "ck-ledger-1", -50000L, PointReason.BOOKING_USE);

        assertThat(inserted).isTrue();
    }

    @Test
    void duplicateInsertReturnsFalse() {
        pointLedgerRepository.tryInsert("test-user-1", "ck-ledger-1", -50000L, PointReason.BOOKING_USE);

        boolean inserted = pointLedgerRepository.tryInsert(
                "test-user-1", "ck-ledger-1", -50000L, PointReason.BOOKING_USE);

        assertThat(inserted).isFalse();
    }

    @Test
    void differentReasonOnSameCheckoutIsAllowed() {
        pointLedgerRepository.tryInsert("test-user-1", "ck-ledger-1", -50000L, PointReason.BOOKING_USE);

        boolean refundInserted = pointLedgerRepository.tryInsert(
                "test-user-1", "ck-ledger-1", 50000L, PointReason.BOOKING_REFUND);

        assertThat(refundInserted).isTrue();
    }

    @Test
    void findsLedgerByCheckoutAndReason() {
        pointLedgerRepository.tryInsert("test-user-1", "ck-ledger-1", -50000L, PointReason.BOOKING_USE);

        PointLedger ledger = pointLedgerRepository
                .findByCheckoutIdAndReason("ck-ledger-1", PointReason.BOOKING_USE)
                .orElseThrow();

        assertThat(ledger.userId()).isEqualTo("test-user-1");
        assertThat(ledger.amount()).isEqualTo(-50000L);
        assertThat(ledger.reason()).isEqualTo(PointReason.BOOKING_USE);
    }

    @Test
    void returnsEmptyWhenLedgerMissing() {
        assertThat(pointLedgerRepository.findByCheckoutIdAndReason(
                "ck-ledger-1", PointReason.BOOKING_REFUND)).isEmpty();
    }
}
