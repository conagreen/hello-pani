package com.example.hellopani.point.infra;

import java.util.Optional;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.DataClassRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import com.example.hellopani.point.domain.PointAccount;

@Repository
public class PointRepository {

    private static final RowMapper<PointAccount> ROW_MAPPER = DataClassRowMapper.newInstance(PointAccount.class);

    private final JdbcTemplate jdbcTemplate;

    public PointRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<PointAccount> findByUserId(String userId) {
        try {
            PointAccount account = jdbcTemplate.queryForObject(
                    "SELECT user_id, balance, updated_at FROM point_account WHERE user_id = ?",
                    ROW_MAPPER, userId);
            return Optional.ofNullable(account);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }
}