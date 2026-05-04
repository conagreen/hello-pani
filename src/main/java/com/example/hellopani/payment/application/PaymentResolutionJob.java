package com.example.hellopani.payment.application;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.example.hellopani.booking.application.BookingResultPayload;
import com.example.hellopani.booking.application.IdempotencyService;
import com.example.hellopani.booking.domain.Booking;
import com.example.hellopani.booking.infra.BookingRepository;
import com.example.hellopani.checkout.infra.CheckoutRepository;
import com.example.hellopani.compensation.application.CompensationContext;
import com.example.hellopani.compensation.application.CompensationService;
import com.example.hellopani.inventory.application.InventoryProperties;
import com.example.hellopani.payment.domain.FailureReason;
import com.example.hellopani.payment.domain.Payment;
import com.example.hellopani.payment.domain.PaymentComponent;
import com.example.hellopani.payment.domain.PaymentComponentStatus;
import com.example.hellopani.payment.domain.PaymentMethodType;
import com.example.hellopani.payment.domain.PaymentStatus;
import com.example.hellopani.payment.domain.PgChargeResult;
import com.example.hellopani.payment.domain.PgClient;
import com.example.hellopani.payment.infra.PaymentComponentRepository;
import com.example.hellopani.payment.infra.PaymentRepository;
import tools.jackson.databind.ObjectMapper;

@Component
public class PaymentResolutionJob {

    private static final Logger log = LoggerFactory.getLogger(PaymentResolutionJob.class);

    private final PaymentRepository paymentRepository;
    private final PaymentComponentRepository componentRepository;
    private final BookingRepository bookingRepository;
    private final CheckoutRepository checkoutRepository;
    private final PgClient pgClient;
    private final CompensationService compensationService;
    private final StringRedisTemplate redisTemplate;
    private final InventoryProperties inventoryProperties;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;
    private final IdempotencyService idempotencyService;
    private final ObjectMapper objectMapper;

    public PaymentResolutionJob(PaymentRepository paymentRepository,
                                PaymentComponentRepository componentRepository,
                                BookingRepository bookingRepository,
                                CheckoutRepository checkoutRepository,
                                PgClient pgClient,
                                CompensationService compensationService,
                                StringRedisTemplate redisTemplate,
                                InventoryProperties inventoryProperties,
                                PlatformTransactionManager transactionManager,
                                Clock clock,
                                IdempotencyService idempotencyService,
                                ObjectMapper objectMapper) {
        this.paymentRepository = paymentRepository;
        this.componentRepository = componentRepository;
        this.bookingRepository = bookingRepository;
        this.checkoutRepository = checkoutRepository;
        this.pgClient = pgClient;
        this.compensationService = compensationService;
        this.redisTemplate = redisTemplate;
        this.inventoryProperties = inventoryProperties;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.clock = clock;
        this.idempotencyService = idempotencyService;
        this.objectMapper = objectMapper;
    }

    @Scheduled(fixedDelayString = "${app.payment.resolution-interval-ms:30000}")
    public void resolveAllPending() {
        List<Payment> pending = paymentRepository.findAllByStatus(PaymentStatus.RESULT_PENDING);
        for (Payment payment : pending) {
            try {
                resolveOne(payment);
            } catch (Exception e) {
                log.error("Failed to resolve pending payment paymentId={} checkoutId={}",
                        payment.paymentId(), payment.checkoutId(), e);
            }
        }
    }

    public void resolveOne(Payment payment) {
        PgChargeResult result = pgClient.lookupResult(payment.pgIdempotencyKey());
        switch (result) {
            case PgChargeResult.Approved a -> confirm(payment, a.externalTransactionId());
            case PgChargeResult.Declined d -> compensateFailed(payment);
            case PgChargeResult.Pending p -> extendHoldTtl(payment.checkoutId());
            case PgChargeResult.NotFound n -> extendHoldTtl(payment.checkoutId());
        }
    }

    private void confirm(Payment payment, String externalTransactionId) {
        transactionTemplate.executeWithoutResult(status -> {
            for (PaymentComponent component : componentRepository.findByPaymentId(payment.paymentId())) {
                if (component.status() == PaymentComponentStatus.PENDING) {
                    componentRepository.markSucceeded(component.paymentComponentId(), externalTransactionId);
                }
            }
            paymentRepository.markCompleted(payment.paymentId(), PaymentStatus.SUCCEEDED, LocalDateTime.now(clock));
            bookingRepository.markConfirmed(payment.bookingId(), LocalDateTime.now(clock));
            checkoutRepository.markUsed(payment.checkoutId());
        });
        // PENDING 응답을 받았던 사용자가 같은 checkoutId로 재요청하면 최신 CONFIRMED 결과를 보게 한다.
        refreshCachedResult(payment, BookingResultPayload.STATUS_CONFIRMED, null);
    }

    private void compensateFailed(Payment payment) {
        long pointAmount = pointAmountToRefund(payment);
        Booking booking = bookingRepository.findById(payment.bookingId()).orElseThrow();

        // RESULT_PENDING → FAILED 마킹 + booking FAILED 마킹. COMPENSATING / COMPENSATED 전이는
        // CompensationService가 DB stock + Redis gate 복구를 끝낸 뒤에 닫는다.
        transactionTemplate.executeWithoutResult(status -> {
            for (PaymentComponent component : componentRepository.findByPaymentId(payment.paymentId())) {
                if (component.status() == PaymentComponentStatus.PENDING) {
                    componentRepository.markFailed(component.paymentComponentId());
                }
            }
            paymentRepository.markCompleted(payment.paymentId(), PaymentStatus.FAILED, LocalDateTime.now(clock));
            bookingRepository.markFailed(payment.bookingId());
        });

        compensationService.compensate(new CompensationContext(
                payment.checkoutId(), payment.userId(), booking.productId(),
                pointAmount, payment.paymentId()));
        refreshCachedResult(payment, BookingResultPayload.STATUS_FAILED,
                FailureReason.CARD_DECLINED.name());
    }

    private void refreshCachedResult(Payment payment, String status, String reason) {
        BookingResultPayload payload = new BookingResultPayload(
                payment.checkoutId(), status, payment.bookingId(), payment.paymentId(), reason);
        try {
            idempotencyService.completeWithResult(
                    payment.checkoutId(), objectMapper.writeValueAsString(payload));
        } catch (RuntimeException e) {
            // Redis 장애 시에도 PG 결과 마감 자체는 진행한다. 다음 PaymentResolutionJob 사이클에서 다시 시도한다.
            log.warn("Failed to refresh idempotency cache for checkoutId={}: {}",
                    payment.checkoutId(), e.getMessage());
        }
    }

    private long pointAmountToRefund(Payment payment) {
        return componentRepository.findByPaymentId(payment.paymentId()).stream()
                .filter(c -> c.method() == PaymentMethodType.POINT)
                .filter(c -> c.status() == PaymentComponentStatus.SUCCEEDED)
                .mapToLong(PaymentComponent::amount)
                .findFirst()
                .orElse(0L);
    }

    private void extendHoldTtl(String checkoutId) {
        try {
            redisTemplate.expire("hold:" + checkoutId,
                    Duration.ofMinutes(inventoryProperties.holdTtlMinutes()));
        } catch (Exception e) {
            log.warn("Failed to extend hold TTL for checkoutId={}: {}", checkoutId, e.getMessage());
        }
    }
}
