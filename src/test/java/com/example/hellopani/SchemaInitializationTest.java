package com.example.hellopani;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@DisplayName("schema.sql 기반 8개 테이블과 seed 데이터 초기화 검증")
class SchemaInitializationTest {

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("도메인 8개 테이블이 모두 생성된다")
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
    @DisplayName("seed 데이터(상품 1건, 재고 10, 테스트 사용자 포인트 50,000)가 적재된다")
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
