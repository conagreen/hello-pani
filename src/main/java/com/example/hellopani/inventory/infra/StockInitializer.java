package com.example.hellopani.inventory.infra;

import java.util.List;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import com.example.hellopani.inventory.domain.Stock;

@Component
public class StockInitializer {

    private final JdbcTemplate jdbcTemplate;
    private final StringRedisTemplate redisTemplate;

    public StockInitializer(JdbcTemplate jdbcTemplate, StringRedisTemplate redisTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.redisTemplate = redisTemplate;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void initializeOnStartup() {
        seedAll();
    }

    public void seedAll() {
        List<Stock> stocks = jdbcTemplate.query(
                "SELECT product_id, qty, updated_at FROM stock",
                (rs, rowNum) -> new Stock(
                        rs.getLong("product_id"),
                        rs.getInt("qty"),
                        rs.getTimestamp("updated_at").toLocalDateTime()));
        for (Stock stock : stocks) {
            String key = RedisStockGate.stockKey(stock.productId());
            redisTemplate.opsForValue().setIfAbsent(key, Integer.toString(stock.qty()));
        }
    }
}
