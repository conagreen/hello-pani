package com.example.hellopani.inventory.infra;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@DisplayName("StockInitializer — 부팅 시 DB stock을 Redis 게이트로 1회 초기화")
class StockInitializerTest {

    @Autowired
    StockInitializer stockInitializer;

    @Autowired
    StringRedisTemplate redisTemplate;

    @Test
    @DisplayName("Redis 키가 없을 때 seedAll은 DB stock.qty를 SET한다")
    void seedAllSetsStockKeyWhenAbsent() {
        redisTemplate.delete("stock:1");

        stockInitializer.seedAll();

        assertThat(redisTemplate.opsForValue().get("stock:1")).isEqualTo("10");
    }

    @Test
    @DisplayName("Redis 키가 이미 있으면 seedAll은 기존 값을 덮어쓰지 않는다 (SETNX 멱등)")
    void seedAllDoesNotOverrideExistingKey() {
        redisTemplate.opsForValue().set("stock:1", "5");

        stockInitializer.seedAll();

        assertThat(redisTemplate.opsForValue().get("stock:1")).isEqualTo("5");
    }
}
