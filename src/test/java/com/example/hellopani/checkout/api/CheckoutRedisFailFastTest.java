package com.example.hellopani.checkout.api;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import com.example.hellopani.checkout.infra.CheckoutCache;
import com.example.hellopani.inventory.domain.RedisUnavailableException;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("GET /checkout — Redis 장애 시 503 + Retry-After (DECISIONS 쟁점 5)")
class CheckoutRedisFailFastTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    CheckoutCache checkoutCache;

    @Test
    @DisplayName("CheckoutCache.put이 RedisUnavailableException을 던지면 503 + Retry-After 헤더로 응답한다")
    void redisUnavailable_returns503WithRetryAfter() throws Exception {
        doThrow(new RedisUnavailableException("simulated outage", null))
                .when(checkoutCache).put(anyString(), anyString(), any(Duration.class));

        mockMvc.perform(get("/checkout")
                        .param("productId", "1")
                        .header("X-User-Id", "test-user-1"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().exists("Retry-After"))
                .andExpect(header().string("Retry-After", "5"))
                .andExpect(jsonPath("$.message").value("Service temporarily unavailable"));
    }
}
