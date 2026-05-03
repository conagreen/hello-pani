package com.example.hellopani.checkout.application;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;
import org.springframework.stereotype.Service;
import com.example.hellopani.catalog.domain.Product;
import com.example.hellopani.catalog.infra.ProductRepository;
import com.example.hellopani.checkout.domain.Checkout;
import com.example.hellopani.checkout.domain.CheckoutStatus;
import com.example.hellopani.checkout.domain.ProductNotFoundException;
import com.example.hellopani.checkout.infra.CheckoutRepository;
import com.example.hellopani.point.domain.PointAccount;
import com.example.hellopani.point.infra.PointRepository;

@Service
public class CheckoutService {

    private static final Duration EXPIRY_DURATION = Duration.ofMinutes(10);

    private final ProductRepository productRepository;
    private final PointRepository pointRepository;
    private final CheckoutRepository checkoutRepository;
    private final Clock clock;

    public CheckoutService(ProductRepository productRepository,
                           PointRepository pointRepository,
                           CheckoutRepository checkoutRepository,
                           Clock clock) {
        this.productRepository = productRepository;
        this.pointRepository = pointRepository;
        this.checkoutRepository = checkoutRepository;
        this.clock = clock;
    }

    public CheckoutResult issue(String userId, long productId) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new ProductNotFoundException(productId));

        long availablePoint = pointRepository.findByUserId(userId)
                .map(PointAccount::balance)
                .orElse(0L);

        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime expiresAt = now.plus(EXPIRY_DURATION);
        String checkoutId = UUID.randomUUID().toString();

        Checkout checkout = new Checkout(
                checkoutId,
                userId,
                productId,
                product.price(),
                availablePoint,
                CheckoutStatus.ISSUED,
                expiresAt,
                now
        );
        checkoutRepository.insert(checkout);

        return new CheckoutResult(checkoutId, product, availablePoint, expiresAt);
    }
}
