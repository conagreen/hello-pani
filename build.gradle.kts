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
}
