package com.example.hellopani.checkout.api;

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
class CheckoutControllerTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Test
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
    void rejectsRequestWithoutUserHeader() throws Exception {
        mockMvc.perform(get("/checkout").param("productId", "1"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void returns404ForUnknownProduct() throws Exception {
        mockMvc.perform(get("/checkout")
                        .param("productId", "999")
                        .header("X-User-Id", "test-user-1"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Product not found: 999"));
    }

    @Test
    void issuesDifferentCheckoutIdEachCall() throws Exception {
        String first = issuedCheckoutId();
        String second = issuedCheckoutId();

        assertThat(first).isNotBlank();
        assertThat(second).isNotBlank();
        assertThat(first).isNotEqualTo(second);
    }

    @Test
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
