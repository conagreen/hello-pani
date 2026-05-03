package com.example.hellopani;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class SchemaInitializationTest {

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    void allDomainTablesExist() {
        List<String> tables = List.of(
                "PRODUCT", "STOCK", "CHECKOUT", "BOOKING",
                "PAYMENT", "PAYMENT_COMPONENT", "POINT_ACCOUNT", "POINT_LEDGER"
        );
        for (String table : tables) {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.tables WHERE UPPER(table_name) = ?",
                    Integer.class, table);
            assertThat(count).as("table %s exists", table).isGreaterThanOrEqualTo(1);
        }
    }

    @Test
    void seedDataLoaded() {
        Integer products = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM product WHERE product_id = 1", Integer.class);
        assertThat(products).isEqualTo(1);

        Integer qty = jdbcTemplate.queryForObject(
                "SELECT qty FROM stock WHERE product_id = 1", Integer.class);
        assertThat(qty).isEqualTo(10);

        Integer accounts = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM point_account WHERE user_id = 'test-user-1'", Integer.class);
        assertThat(accounts).isEqualTo(1);
    }
}
