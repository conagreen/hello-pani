package com.example.hellopani.booking.infra;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import com.example.hellopani.booking.domain.Booking;
import com.example.hellopani.booking.domain.BookingStatus;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Optional;

@Repository
public class BookingRepository {

    private static final RowMapper<Booking> ROW_MAPPER = (rs, rowNum) -> new Booking(
            rs.getLong("booking_id"),
            rs.getString("checkout_id"),
            rs.getString("user_id"),
            rs.getLong("product_id"),
            BookingStatus.valueOf(rs.getString("status")),
            rs.getLong("total_amount"),
            rs.getTimestamp("created_at").toLocalDateTime(),
            Optional.ofNullable(rs.getTimestamp("confirmed_at")).map(Timestamp::toLocalDateTime).orElse(null)
    );

    private final JdbcTemplate jdbcTemplate;

    public BookingRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public long insertPending(String checkoutId, String userId, long productId, long totalAmount) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO booking "
                            + "(checkout_id, user_id, product_id, status, total_amount) "
                            + "VALUES (?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, checkoutId);
            ps.setString(2, userId);
            ps.setLong(3, productId);
            ps.setString(4, BookingStatus.PENDING_PAYMENT.name());
            ps.setLong(5, totalAmount);
            return ps;
        }, keyHolder);
        return Objects.requireNonNull(keyHolder.getKey()).longValue();
    }

    public int markConfirmed(long bookingId, LocalDateTime confirmedAt) {
        return jdbcTemplate.update(
                "UPDATE booking SET status = ?, confirmed_at = ? WHERE booking_id = ?",
                BookingStatus.CONFIRMED.name(), confirmedAt, bookingId);
    }

    public int markFailed(long bookingId) {
        return jdbcTemplate.update(
                "UPDATE booking SET status = ? WHERE booking_id = ?",
                BookingStatus.FAILED.name(), bookingId);
    }

    public Optional<Booking> findById(long bookingId) {
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject(
                    selectAll() + " WHERE booking_id = ?", ROW_MAPPER, bookingId));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public Optional<Booking> findByCheckoutId(String checkoutId) {
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject(
                    selectAll() + " WHERE checkout_id = ?", ROW_MAPPER, checkoutId));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    private static String selectAll() {
        return "SELECT booking_id, checkout_id, user_id, product_id, status, "
                + "total_amount, created_at, confirmed_at FROM booking";
    }
}
