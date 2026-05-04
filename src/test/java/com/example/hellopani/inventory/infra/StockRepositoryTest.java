package com.example.hellopani.inventory.infra;

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
class StockRepositoryTest {

    @Autowired
    StockRepository stockRepository;

    @Test
    void findsSeededStock() {
        Stock stock = stockRepository.findByProductId(1L).orElseThrow();
        assertThat(stock.productId()).isEqualTo(1L);
        assertThat(stock.qty()).isEqualTo(10);
        assertThat(stock.updatedAt()).isNotNull();
    }

    @Test
    void returnsEmptyWhenProductMissing() {
        assertThat(stockRepository.findByProductId(999L)).isEmpty();
    }

    @Test
    void decrementsWhenStockAvailable() {
        int affected = stockRepository.decrement(1L);
        assertThat(affected).isEqualTo(1);
        assertThat(stockRepository.findByProductId(1L).orElseThrow().qty()).isEqualTo(9);
    }

    @Test
    void decrementBlocksAfterTen() {
        for (int i = 0; i < 10; i++) {
            assertThat(stockRepository.decrement(1L)).isEqualTo(1);
        }
        assertThat(stockRepository.decrement(1L)).isEqualTo(0);
        assertThat(stockRepository.findByProductId(1L).orElseThrow().qty()).isZero();
    }

    @Test
    void incrementRestoresStock() {
        stockRepository.decrement(1L);
        stockRepository.decrement(1L);
        assertThat(stockRepository.findByProductId(1L).orElseThrow().qty()).isEqualTo(8);

        int affected = stockRepository.increment(1L);
        assertThat(affected).isEqualTo(1);
        assertThat(stockRepository.findByProductId(1L).orElseThrow().qty()).isEqualTo(9);
    }
}
