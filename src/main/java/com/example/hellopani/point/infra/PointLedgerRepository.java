package com.example.hellopani.point.infra;

import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import com.example.hellopani.point.domain.PointLedger;
import com.example.hellopani.point.domain.PointReason;

@Repository
public class PointLedgerRepository {

    private static final RowMapper<PointLedger> ROW_MAPPER = (rs, rowNum) -> new PointLedger(
            rs.getLong("point_ledger_id"),
            rs.getString("user_id"),
            rs.getString("checkout_id"),
            rs.getLong("amount"),
            PointReason.valueOf(rs.getString("reason")),
            rs.getTimestamp("created_at").toLocalDateTime()
    );

    private final JdbcTemplate jdbcTemplate;

    public PointLedgerRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public boolean tryInsert(String userId, String checkoutId, long amount, PointReason reason) {
        try {
            jdbcTemplate.update(
                    "INSERT INTO point_ledger (user_id, checkout_id, amount, reason) "
                            + "VALUES (?, ?, ?, ?)",
                    userId, checkoutId, amount, reason.name());
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    public Optional<PointLedger> findByCheckoutIdAndReason(String checkoutId, PointReason reason) {
        try {
            PointLedger ledger = jdbcTemplate.queryForObject(
                    "SELECT point_ledger_id, user_id, checkout_id, amount, reason, created_at "
                            + "FROM point_ledger WHERE checkout_id = ? AND reason = ?",
                    ROW_MAPPER, checkoutId, reason.name());
            return Optional.ofNullable(ledger);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }
}
