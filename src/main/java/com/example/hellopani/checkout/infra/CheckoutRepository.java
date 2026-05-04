package com.example.hellopani.checkout.infra;

import java.util.Optional;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import com.example.hellopani.checkout.domain.Checkout;
import com.example.hellopani.checkout.domain.CheckoutStatus;

@Repository
public class CheckoutRepository {

    private static final RowMapper<Checkout> ROW_MAPPER = (rs, rowNum) -> new Checkout(
            rs.getString("checkout_id"),
            rs.getString("user_id"),
            rs.getLong("product_id"),
            rs.getLong("quoted_price"),
            rs.getLong("available_point_snapshot"),
            CheckoutStatus.valueOf(rs.getString("status")),
            rs.getTimestamp("expires_at").toLocalDateTime(),
            rs.getTimestamp("created_at").toLocalDateTime()
    );

    private final JdbcTemplate jdbcTemplate;

    public CheckoutRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void insert(Checkout checkout) {
        jdbcTemplate.update(
                "INSERT INTO checkout "
                        + "(checkout_id, user_id, product_id, quoted_price, available_point_snapshot, status, expires_at, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                checkout.checkoutId(),
                checkout.userId(),
                checkout.productId(),
                checkout.quotedPrice(),
                checkout.availablePointSnapshot(),
                checkout.status().name(),
                checkout.expiresAt(),
                checkout.createdAt()
        );
    }

    public Optional<Checkout> findById(String checkoutId) {
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject(
                    "SELECT checkout_id, user_id, product_id, quoted_price, available_point_snapshot, "
                            + "status, expires_at, created_at FROM checkout WHERE checkout_id = ?",
                    ROW_MAPPER, checkoutId));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public int markUsed(String checkoutId) {
        return jdbcTemplate.update(
                "UPDATE checkout SET status = ? WHERE checkout_id = ?",
                CheckoutStatus.USED.name(), checkoutId);
    }
}
