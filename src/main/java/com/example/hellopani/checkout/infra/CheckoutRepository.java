package com.example.hellopani.checkout.infra;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import com.example.hellopani.checkout.domain.Checkout;

@Repository
public class CheckoutRepository {

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
}
