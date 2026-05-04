package com.example.hellopani.inventory.infra;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.context.annotation.Import;
import com.example.hellopani.inventory.domain.Stock;

import static org.assertj.core.api.Assertions.assertThat;

@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(StockRepository.class)
@DisplayName("StockRepository — 조건부 UPDATE 기반 재고 선점과 복구")
class StockRepositoryTest {

    @Autowired
    StockRepository stockRepository;

    @Test
    @DisplayName("seed로 등록된 재고 정보를 productId로 조회한다")
    void findsSeededStock() {
        Stock stock = stockRepository.findByProductId(1L).orElseThrow();
        assertThat(stock.productId()).isEqualTo(1L);
        assertThat(stock.qty()).isEqualTo(10);
        assertThat(stock.updatedAt()).isNotNull();
    }

    @Test
    @DisplayName("존재하지 않는 productId는 빈 Optional을 반환한다")
    void returnsEmptyWhenProductMissing() {
        assertThat(stockRepository.findByProductId(999L)).isEmpty();
    }

    @Test
    @DisplayName("재고가 남은 상태에서 decrement 호출 시 영향 row 1과 함께 qty가 1 감소한다")
    void decrementsWhenStockAvailable() {
        int affected = stockRepository.decrement(1L);
        assertThat(affected).isEqualTo(1);
        assertThat(stockRepository.findByProductId(1L).orElseThrow().qty()).isEqualTo(9);
    }

    @Test
    @DisplayName("10번 차감 후 11번째 decrement는 영향 row 0을 반환하며 qty는 0 미만으로 떨어지지 않는다")
    void decrementBlocksAfterTen() {
        for (int i = 0; i < 10; i++) {
            assertThat(stockRepository.decrement(1L)).isEqualTo(1);
        }
        assertThat(stockRepository.decrement(1L)).isEqualTo(0);
        assertThat(stockRepository.findByProductId(1L).orElseThrow().qty()).isZero();
    }

    @Test
    @DisplayName("increment는 qty를 1 증가시키고 영향 row 1을 반환한다 (보상 경로)")
    void incrementRestoresStock() {
        stockRepository.decrement(1L);
        stockRepository.decrement(1L);
        assertThat(stockRepository.findByProductId(1L).orElseThrow().qty()).isEqualTo(8);

        int affected = stockRepository.increment(1L);
        assertThat(affected).isEqualTo(1);
        assertThat(stockRepository.findByProductId(1L).orElseThrow().qty()).isEqualTo(9);
    }
}
