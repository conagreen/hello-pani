package com.example.hellopani.checkout.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("GET /checkout — 주문서 발급 API 계약과 응답 shape")
class CheckoutControllerTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Test
    @DisplayName("정상 호출 시 200과 함께 약속된 응답 필드를 모두 반환한다")
    void issuesCheckoutForKnownUserAndProduct() throws Exception {
        mockMvc.perform(get("/checkout")
                        .param("productId", "1")
                        .header("X-User-Id", "test-user-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.checkoutId").isNotEmpty())
                .andExpect(jsonPath("$.product.name").value("한정 패키지"))
                .andExpect(jsonPath("$.product.price").value(150000))
                .andExpect(jsonPath("$.product.imageUrl").value("https://example.com/p1.jpg"))
                .andExpect(jsonPath("$.product.checkInAt").isNotEmpty())
                .andExpect(jsonPath("$.product.checkOutAt").isNotEmpty())
                .andExpect(jsonPath("$.availablePoint").value(50000))
                .andExpect(jsonPath("$.expiresAt").isNotEmpty());
    }

    @Test
    @DisplayName("X-User-Id 헤더가 없으면 400 Bad Request를 반환한다")
    void rejectsRequestWithoutUserHeader() throws Exception {
        mockMvc.perform(get("/checkout").param("productId", "1"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("존재하지 않는 productId는 404와 함께 도메인 에러 메시지를 반환한다")
    void returns404ForUnknownProduct() throws Exception {
        mockMvc.perform(get("/checkout")
                        .param("productId", "999")
                        .header("X-User-Id", "test-user-1"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Product not found: 999"));
    }

    @Test
    @DisplayName("같은 사용자가 여러 번 호출하면 매번 다른 checkoutId가 발급된다 (비멱등)")
    void issuesDifferentCheckoutIdEachCall() throws Exception {
        String first = issuedCheckoutId();
        String second = issuedCheckoutId();

        assertThat(first).isNotBlank();
        assertThat(second).isNotBlank();
        assertThat(first).isNotEqualTo(second);
    }

    @Test
    @DisplayName("응답 JSON 어디에도 잔여 재고 정보(stock / qty / remaining 등)가 포함되지 않는다")
    void doesNotExposeRemainingStock() throws Exception {
        MvcResult result = mockMvc.perform(get("/checkout")
                        .param("productId", "1")
                        .header("X-User-Id", "test-user-1"))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());

        assertThat(findRecursively(body, "stock")).isFalse();
        assertThat(findRecursively(body, "qty")).isFalse();
        assertThat(findRecursively(body, "remaining")).isFalse();
        assertThat(findRecursively(body, "remainingStock")).isFalse();
        assertThat(findRecursively(body, "availableStock")).isFalse();
    }

    @Test
    @DisplayName("응답 shape는 약속된 4개 top-level 필드와 product 5개 필드만 포함한다")
    void responseShapeMatchesContract() throws Exception {
        MvcResult result = mockMvc.perform(get("/checkout")
                        .param("productId", "1")
                        .header("X-User-Id", "test-user-1"))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());

        assertThat(fieldNames(body)).containsExactlyInAnyOrder(
                "checkoutId", "product", "availablePoint", "expiresAt");
        assertThat(fieldNames(body.get("product"))).containsExactlyInAnyOrder(
                "name", "price", "imageUrl", "checkInAt", "checkOutAt");
    }

    @Test
    @DisplayName("PointAccount가 없는 신규 사용자도 availablePoint=0으로 정상 응답한다")
    void returnsZeroAvailablePointForUnknownUser() throws Exception {
        mockMvc.perform(get("/checkout")
                        .param("productId", "1")
                        .header("X-User-Id", "brand-new-user"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.availablePoint").value(0));
    }

    private String issuedCheckoutId() throws Exception {
        MvcResult result = mockMvc.perform(get("/checkout")
                        .param("productId", "1")
                        .header("X-User-Id", "test-user-1"))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("checkoutId").asText();
    }

    private boolean findRecursively(JsonNode node, String fieldName) {
        if (node == null) return false;
        if (node.has(fieldName)) return true;
        for (JsonNode child : node) {
            if (findRecursively(child, fieldName)) return true;
        }
        return false;
    }

    private java.util.Set<String> fieldNames(JsonNode node) {
        java.util.Set<String> names = new java.util.LinkedHashSet<>();
        node.propertyNames().forEach(names::add);
        return names;
    }
}
