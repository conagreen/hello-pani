package com.example.hellopani.catalog.infra;

import java.util.Optional;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.DataClassRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import com.example.hellopani.catalog.domain.Product;


@Repository
public class ProductRepository {

    private static final RowMapper<Product> ROW_MAPPER = DataClassRowMapper.newInstance(Product.class);

    private final JdbcTemplate jdbcTemplate;

    public ProductRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<Product> findById(long productId) {
        try {
            Product product = jdbcTemplate.queryForObject(
                    "SELECT product_id, name, price, image_url, check_in_at, check_out_at, sales_open_at "
                            + "FROM product WHERE product_id = ?",
                    ROW_MAPPER, productId);
            return Optional.ofNullable(product);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }
}
