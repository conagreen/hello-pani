package com.example.hellopani.catalog.infra;

import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.context.annotation.Import;
import com.example.hellopani.catalog.domain.Product;

import static org.assertj.core.api.Assertions.assertThat;

@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(ProductRepository.class)
@DisplayName("ProductRepository — 상품 조회")
class ProductRepositoryTest {

    @Autowired
    ProductRepository productRepository;

    @Test
    @DisplayName("seed로 등록된 상품을 productId로 조회한다")
    void findsExistingSeedProduct() {
        Optional<Product> result = productRepository.findById(1L);

        assertThat(result).isPresent();
        Product product = result.get();
        assertThat(product.productId()).isEqualTo(1L);
        assertThat(product.name()).isEqualTo("한정 패키지");
        assertThat(product.price()).isEqualTo(150000L);
        assertThat(product.imageUrl()).isEqualTo("https://example.com/p1.jpg");
        assertThat(product.checkInAt()).isNotNull();
        assertThat(product.checkOutAt()).isNotNull();
        assertThat(product.salesOpenAt()).isNotNull();
    }

    @Test
    @DisplayName("존재하지 않는 productId는 빈 Optional을 반환한다")
    void returnsEmptyWhenProductMissing() {
        assertThat(productRepository.findById(999L)).isEmpty();
    }
}