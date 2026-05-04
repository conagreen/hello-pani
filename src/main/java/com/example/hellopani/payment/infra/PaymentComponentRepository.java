package com.example.hellopani.payment.infra;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import com.example.hellopani.payment.domain.PaymentComponent;
import com.example.hellopani.payment.domain.PaymentComponentStatus;
import com.example.hellopani.payment.domain.PaymentMethodType;

@Repository
public class PaymentComponentRepository {

    private static final RowMapper<PaymentComponent> ROW_MAPPER = (rs, rowNum) -> new PaymentComponent(
            rs.getLong("payment_component_id"),
            rs.getLong("payment_id"),
            PaymentMethodType.valueOf(rs.getString("method")),
            rs.getLong("amount"),
            PaymentComponentStatus.valueOf(rs.getString("status")),
            rs.getString("external_transaction_id")
    );

    private final JdbcTemplate jdbcTemplate;

    public PaymentComponentRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public long insertPending(long paymentId, PaymentMethodType method, long amount) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO payment_component "
                            + "(payment_id, method, amount, status, external_transaction_id) "
                            + "VALUES (?, ?, ?, ?, NULL)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, paymentId);
            ps.setString(2, method.name());
            ps.setLong(3, amount);
            ps.setString(4, PaymentComponentStatus.PENDING.name());
            return ps;
        }, keyHolder);
        return Objects.requireNonNull(keyHolder.getKey()).longValue();
    }

    public int markSucceeded(long componentId, String externalTransactionId) {
        return jdbcTemplate.update(
                "UPDATE payment_component SET status = ?, external_transaction_id = ? "
                        + "WHERE payment_component_id = ?",
                PaymentComponentStatus.SUCCEEDED.name(), externalTransactionId, componentId);
    }

    public int markFailed(long componentId) {
        return jdbcTemplate.update(
                "UPDATE payment_component SET status = ? WHERE payment_component_id = ?",
                PaymentComponentStatus.FAILED.name(), componentId);
    }

    public List<PaymentComponent> findByPaymentId(long paymentId) {
        return jdbcTemplate.query(
                "SELECT payment_component_id, payment_id, method, amount, status, external_transaction_id "
                        + "FROM payment_component WHERE payment_id = ? ORDER BY payment_component_id",
                ROW_MAPPER, paymentId);
    }
}
