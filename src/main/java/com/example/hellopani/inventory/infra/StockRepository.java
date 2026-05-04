package com.example.hellopani.inventory.infra;

import java.util.Optional;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.DataClassRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import com.example.hellopani.inventory.domain.Stock;

@Repository
public class StockRepository {

    private static final RowMapper<Stock> ROW_MAPPER = DataClassRowMapper.newInstance(Stock.class);

    private final JdbcTemplate jdbcTemplate;

    public StockRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<Stock> findByProductId(long productId) {
        try {
            Stock stock = jdbcTemplate.queryForObject(
                    "SELECT product_id, qty, updated_at FROM stock WHERE product_id = ?",
                    ROW_MAPPER, productId);
            return Optional.ofNullable(stock);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public int decrement(long productId) {
        return jdbcTemplate.update(
                "UPDATE stock SET qty = qty - 1, updated_at = CURRENT_TIMESTAMP(6) "
                        + "WHERE product_id = ? AND qty > 0",
                productId);
    }

    public int increment(long productId) {
        return jdbcTemplate.update(
                "UPDATE stock SET qty = qty + 1, updated_at = CURRENT_TIMESTAMP(6) "
                        + "WHERE product_id = ?",
                productId);
    }
}
