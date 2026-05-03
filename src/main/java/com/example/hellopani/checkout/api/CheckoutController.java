package com.example.hellopani.checkout.api;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import com.example.hellopani.checkout.application.CheckoutResult;
import com.example.hellopani.checkout.application.CheckoutService;
import com.example.hellopani.checkout.domain.ProductNotFoundException;

@RestController
public class CheckoutController {

    private final CheckoutService checkoutService;

    public CheckoutController(CheckoutService checkoutService) {
        this.checkoutService = checkoutService;
    }

    @GetMapping("/checkout")
    public CheckoutResponse getCheckout(
            @RequestHeader("X-User-Id") String userId,
            @RequestParam("productId") long productId) {
        CheckoutResult result = checkoutService.issue(userId, productId);
        return CheckoutResponse.from(result);
    }

    @ExceptionHandler(ProductNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ErrorResponse productNotFound(ProductNotFoundException e) {
        return new ErrorResponse(e.getMessage());
    }
}
