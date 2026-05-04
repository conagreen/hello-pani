package com.example.hellopani.compensation.infra;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import com.example.hellopani.compensation.domain.CompensationStep;

@Repository
public class CompensationStepRepository {

    private final JdbcTemplate jdbcTemplate;

    public CompensationStepRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public boolean isCompleted(String checkoutId, CompensationStep step) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM compensation_step WHERE checkout_id = ? AND step = ?",
                Integer.class, checkoutId, step.name());
        return count != null && count > 0;
    }

    /**
     * Records that the given step has been completed for the checkout.
     * Throws DuplicateKeyException if another executor already recorded the same step.
     */
    public void insert(String checkoutId, CompensationStep step) throws DuplicateKeyException {
        jdbcTemplate.update(
                "INSERT INTO compensation_step (checkout_id, step) VALUES (?, ?)",
                checkoutId, step.name());
    }
}
