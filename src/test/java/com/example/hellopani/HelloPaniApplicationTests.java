package com.example.hellopani;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
@DisplayName("Spring Boot 애플리케이션 컨텍스트 부팅 검증")
class HelloPaniApplicationTests {

    @Test
    @DisplayName("Spring 컨텍스트가 모든 빈을 정상적으로 로드한다")
    void contextLoads() {
    }

}
