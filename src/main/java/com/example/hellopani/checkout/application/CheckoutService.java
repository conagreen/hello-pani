package com.example.hellopani.checkout.application;

import org.springframework.stereotype.Service;
import com.example.hellopani.catalog.domain.Product;
import com.example.hellopani.catalog.infra.ProductRepository;
import com.example.hellopani.checkout.infra.CheckoutCache;
import com.example.hellopani.point.domain.PointAccount;
import com.example.hellopani.point.infra.PointRepository;
import com.example.hellopani.checkout.domain.ProductNotFoundException;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * GET /checkout 처리.
 *
 * <p>핵심 약속:
 * <ul>
 *   <li>DB read만 한다 (product, point_account). DB write 없음.</li>
 *   <li>Redis에는 minimal 매핑만 둔다 — checkoutId → userId, TTL 10분.</li>
 *   <li>가격 / 포인트 잔액 같은 캡처 정보는 POST 시점에 재조회로 보장한다.</li>
 *   <li>POST가 게이트를 통과한 경우에만 BookingService.reserveInTransaction이
 *       checkout 행을 DB에 INSERT한다 — 거절 경로 0 DB hit.</li>
 * </ul>
 */
@Service
public class CheckoutService {

    public static final Duration EXPIRY_DURATION = Duration.ofMinutes(10);

    private final ProductRepository productRepository;
    private final PointRepository pointRepository;
    private final CheckoutCache checkoutCache;
    private final Clock clock;

    public CheckoutService(ProductRepository productRepository,
                           PointRepository pointRepository,
                           CheckoutCache checkoutCache,
                           Clock clock) {
        this.productRepository = productRepository;
        this.pointRepository = pointRepository;
        this.checkoutCache = checkoutCache;
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

        checkoutCache.put(checkoutId, userId, EXPIRY_DURATION);

        return new CheckoutResult(checkoutId, product, availablePoint, expiresAt);
    }
}
