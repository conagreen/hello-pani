package com.example.hellopani.inventory.infra;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class StockInitializerTest {

    @Autowired
    StockInitializer stockInitializer;

    @Autowired
    StringRedisTemplate redisTemplate;

    @Test
    void seedAllSetsStockKeyWhenAbsent() {
        redisTemplate.delete("stock:1");

        stockInitializer.seedAll();

        assertThat(redisTemplate.opsForValue().get("stock:1")).isEqualTo("10");
    }

    @Test
    void seedAllDoesNotOverrideExistingKey() {
        redisTemplate.opsForValue().set("stock:1", "5");

        stockInitializer.seedAll();

        assertThat(redisTemplate.opsForValue().get("stock:1")).isEqualTo("5");
    }
}
