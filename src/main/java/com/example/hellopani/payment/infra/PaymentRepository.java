package com.example.hellopani.payment.infra;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import com.example.hellopani.payment.domain.Payment;
import com.example.hellopani.payment.domain.PaymentStatus;

@Repository
public class PaymentRepository {

    private static final RowMapper<Payment> ROW_MAPPER = (rs, rowNum) -> new Payment(
            rs.getLong("payment_id"),
            rs.getString("checkout_id"),
            rs.getLong("booking_id"),
            rs.getString("user_id"),
            PaymentStatus.valueOf(rs.getString("status")),
            rs.getLong("total_amount"),
            rs.getString("pg_idempotency_key"),
            rs.getTimestamp("created_at").toLocalDateTime(),
            Optional.ofNullable(rs.getTimestamp("completed_at")).map(Timestamp::toLocalDateTime).orElse(null)
    );

    private final JdbcTemplate jdbcTemplate;

    public PaymentRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public long insertProcessing(String checkoutId, long bookingId, String userId,
                                 long totalAmount, String pgIdempotencyKey) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO payment "
                            + "(checkout_id, booking_id, user_id, status, total_amount, pg_idempotency_key) "
                            + "VALUES (?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, checkoutId);
            ps.setLong(2, bookingId);
            ps.setString(3, userId);
            ps.setString(4, PaymentStatus.PROCESSING.name());
            ps.setLong(5, totalAmount);
            ps.setString(6, pgIdempotencyKey);
            return ps;
        }, keyHolder);
        return Objects.requireNonNull(keyHolder.getKey()).longValue();
    }

    public int updateStatus(long paymentId, PaymentStatus status) {
        return jdbcTemplate.update(
                "UPDATE payment SET status = ? WHERE payment_id = ?",
                status.name(), paymentId);
    }

    public int markCompleted(long paymentId, PaymentStatus status, LocalDateTime completedAt) {
        return jdbcTemplate.update(
                "UPDATE payment SET status = ?, completed_at = ? WHERE payment_id = ?",
                status.name(), completedAt, paymentId);
    }

    public Optional<Payment> findById(long paymentId) {
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject(
                    selectAll() + " WHERE payment_id = ?", ROW_MAPPER, paymentId));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public Optional<Payment> findByCheckoutId(String checkoutId) {
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject(
                    selectAll() + " WHERE checkout_id = ?", ROW_MAPPER, checkoutId));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public List<Payment> findAllByStatus(PaymentStatus status) {
        return jdbcTemplate.query(
                selectAll() + " WHERE status = ? ORDER BY payment_id",
                ROW_MAPPER, status.name());
    }

    private static String selectAll() {
        return "SELECT payment_id, checkout_id, booking_id, user_id, status, "
                + "total_amount, pg_idempotency_key, created_at, completed_at FROM payment";
    }
}
