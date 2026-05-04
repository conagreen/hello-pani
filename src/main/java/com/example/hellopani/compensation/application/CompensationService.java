package com.example.hellopani.compensation.application;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.LocalDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.example.hellopani.compensation.domain.CompensationStep;
import com.example.hellopani.compensation.infra.CompensationStepRepository;
import com.example.hellopani.inventory.domain.StockGate;
import com.example.hellopani.inventory.infra.StockRepository;
import com.example.hellopani.payment.domain.ChargeOutcome;
import com.example.hellopani.payment.domain.ChargeRequest;
import com.example.hellopani.payment.domain.PaymentMethodType;
import com.example.hellopani.payment.domain.PaymentStatus;
import com.example.hellopani.payment.infra.PaymentRepository;
import com.example.hellopani.payment.infra.PointPayment;

@Service
public class CompensationService {

    private static final Logger log = LoggerFactory.getLogger(CompensationService.class);

    private final PointPayment pointPayment;
    private final StockRepository stockRepository;
    private final StockGate stockGate;
    private final CompensationStepRepository stepRepository;
    private final PaymentRepository paymentRepository;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;
    private final Counter refundFailedCounter;

    public CompensationService(PointPayment pointPayment,
                               StockRepository stockRepository,
                               StockGate stockGate,
                               CompensationStepRepository stepRepository,
                               PaymentRepository paymentRepository,
                               PlatformTransactionManager transactionManager,
                               Clock clock,
                               MeterRegistry meterRegistry) {
        this.pointPayment = pointPayment;
        this.stockRepository = stockRepository;
        this.stockGate = stockGate;
        this.stepRepository = stepRepository;
        this.paymentRepository = paymentRepository;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.clock = clock;
        this.refundFailedCounter = Counter.builder("compensation.refund_failed")
                .description("Number of compensation attempts that ended with REFUND_FAILED")
                .register(meterRegistry);
    }

    public void compensate(CompensationContext ctx) {
        // FAILED → COMPENSATING 전이. 이미 COMPENSATING / COMPENSATED / REFUND_FAILED라면 0 row이고 그대로 진행.
        // 멱등 재실행을 보장하기 위해 조건부 UPDATE를 쓴다.
        paymentRepository.updateStatusIfCurrent(
                ctx.paymentId(), PaymentStatus.FAILED, PaymentStatus.COMPENSATING);
        try {
            if (ctx.shouldRefundPoint()) {
                compensatePoint(ctx);
            }
            compensateDbStock(ctx);
            compensateRedisGate(ctx);
            // 3 단계 모두 성공 → COMPENSATING → COMPENSATED. 이미 COMPENSATED 또는 REFUND_FAILED면 0 row (멱등).
            paymentRepository.markCompletedIfCurrent(
                    ctx.paymentId(), PaymentStatus.COMPENSATING, PaymentStatus.COMPENSATED,
                    LocalDateTime.now(clock));
        } catch (RuntimeException e) {
            markRefundFailed(ctx, e);
            throw e;
        }
    }

    void compensatePoint(CompensationContext ctx) {
        if (stepRepository.isCompleted(ctx.checkoutId(), CompensationStep.POINT_REFUNDED)) {
            return;
        }
        transactionTemplate.executeWithoutResult(status -> {
            ChargeRequest request = new ChargeRequest(
                    ctx.checkoutId(), ctx.userId(),
                    PaymentMethodType.POINT, ctx.pointRefundAmount());
            ChargeOutcome.Succeeded prior = new ChargeOutcome.Succeeded(
                    PaymentMethodType.POINT, "compensation");
            pointPayment.refund(request, prior);
            try {
                stepRepository.insert(ctx.checkoutId(), CompensationStep.POINT_REFUNDED);
            } catch (DuplicateKeyException e) {
                status.setRollbackOnly();
            }
        });
    }

    void compensateDbStock(CompensationContext ctx) {
        if (stepRepository.isCompleted(ctx.checkoutId(), CompensationStep.DB_STOCK_RESTORED)) {
            return;
        }
        transactionTemplate.executeWithoutResult(status -> {
            stockRepository.increment(ctx.productId());
            try {
                stepRepository.insert(ctx.checkoutId(), CompensationStep.DB_STOCK_RESTORED);
            } catch (DuplicateKeyException e) {
                status.setRollbackOnly();
            }
        });
    }

    void compensateRedisGate(CompensationContext ctx) {
        if (stepRepository.isCompleted(ctx.checkoutId(), CompensationStep.REDIS_GATE_RESTORED)) {
            return;
        }
        stockGate.release(ctx.productId(), ctx.checkoutId());
        try {
            stepRepository.insert(ctx.checkoutId(), CompensationStep.REDIS_GATE_RESTORED);
        } catch (DuplicateKeyException e) {
            // Another executor already recorded — treat as success.
        }
    }

    private void markRefundFailed(CompensationContext ctx, RuntimeException cause) {
        log.error("Compensation failed for checkoutId={} paymentId={}",
                ctx.checkoutId(), ctx.paymentId(), cause);
        refundFailedCounter.increment();
        try {
            paymentRepository.updateStatus(ctx.paymentId(), PaymentStatus.REFUND_FAILED);
        } catch (RuntimeException markException) {
            log.error("Failed to mark Payment REFUND_FAILED for paymentId={}",
                    ctx.paymentId(), markException);
        }
    }
}
