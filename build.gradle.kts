plugins {
    java
    id("org.springframework.boot") version "4.0.6"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.example"
version = "0.0.1-SNAPSHOT"
description = "hello-pani"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    // Resilience4j @CircuitBreaker는 Spring AOP의 AspectJ pointcut 표현으로 동작한다.
    // aspectjweaver 없이는 pointcut parser가 *조용히* 실패해 aspect가 적용 안 됨 →
    // fallback이 안 불려서 RedisCommandTimeoutException이 그대로 500으로 전파된다.
    // Spring Boot 4에는 spring-boot-starter-aop이 없으므로 aspectjweaver를 직접 명시.
    implementation("org.aspectj:aspectjweaver")
    implementation("com.mysql:mysql-connector-j")
    implementation("io.github.resilience4j:resilience4j-spring-boot4:2.4.0")
    implementation("io.micrometer:micrometer-registry-prometheus")
    testAndDevelopmentOnly("org.springframework.boot:spring-boot-docker-compose")
    testImplementation("org.springframework.boot:spring-boot-starter-data-redis-test")
    testImplementation("org.springframework.boot:spring-boot-starter-jdbc-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
    useJUnitPlatform()
    // Spring Boot Docker Compose는 테스트에서 기본적으로 기동을 건너뛴다.
    // 외부에서 MySQL/Redis를 미리 띄우지 않아도 ./gradlew test 만으로 검증되게 강제 활성화한다.
    systemProperty("spring.docker.compose.skip.in-tests", "false")
    // 테스트 컨텍스트마다 자기 Hikari 풀을 만든다. 기본 max-pool=10 이라 컨텍스트 4개만 캐시돼도
    // 40 connection. 거기에 review.sh의 app-1/app-2/exporter 등이 더해지면 MySQL max_connections=151을
    // 간헐적으로 초과해 "Too many connections"로 ApplicationContext 로드가 실패한다.
    // 테스트 컨텍스트는 동시 호출이 적으니 풀을 작게 잡아도 충분하다.
    systemProperty("spring.datasource.hikari.maximum-pool-size", "2")
    systemProperty("spring.datasource.hikari.minimum-idle", "0")
}
