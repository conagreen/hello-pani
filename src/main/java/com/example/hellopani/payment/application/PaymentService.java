package com.example.hellopani.payment.application;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.example.hellopani.payment.domain.ChargeOutcome;
import com.example.hellopani.payment.domain.ChargeRequest;
import com.example.hellopani.payment.domain.PaymentMethodType;
import com.example.hellopani.payment.domain.PaymentStatus;
import com.example.hellopani.payment.infra.PaymentComponentRepository;
import com.example.hellopani.payment.infra.PaymentRepository;

@Service
public class PaymentService {

    private final PaymentComposer composer;
    private final PaymentRepository paymentRepository;
    private final PaymentComponentRepository componentRepository;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    public PaymentService(PaymentComposer composer,
                          PaymentRepository paymentRepository,
                          PaymentComponentRepository componentRepository,
                          PlatformTransactionManager transactionManager,
                          Clock clock) {
        this.composer = composer;
        this.paymentRepository = paymentRepository;
        this.componentRepository = componentRepository;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    public PaymentExecutionResult execute(PaymentExecutionContext context) {
        long paymentId = createPaymentRow(context);
        Map<PaymentMethodType, Long> componentIds = createComponentRows(paymentId, context);

        List<ChargeRequest> requests = toChargeRequests(context);
        CompositionResult composition = composer.compose(requests, context.totalAmount());

        return finalize(paymentId, componentIds, composition);
    }

    private long createPaymentRow(PaymentExecutionContext context) {
        return transactionTemplate.execute(status -> paymentRepository.insertProcessing(
                context.checkoutId(),
                context.bookingId(),
                context.userId(),
                context.totalAmount(),
                context.checkoutId()));
    }

    private Map<PaymentMethodType, Long> createComponentRows(long paymentId, PaymentExecutionContext context) {
        return transactionTemplate.execute(status -> {
            Map<PaymentMethodType, Long> ids = new EnumMap<>(PaymentMethodType.class);
            for (PaymentExecutionContext.ComponentRequest cr : context.components()) {
                long id = componentRepository.insertPending(paymentId, cr.type(), cr.amount());
                ids.put(cr.type(), id);
            }
            return ids;
        });
    }

    private List<ChargeRequest> toChargeRequests(PaymentExecutionContext context) {
        List<ChargeRequest> list = new ArrayList<>(context.components().size());
        for (PaymentExecutionContext.ComponentRequest cr : context.components()) {
            list.add(new ChargeRequest(context.checkoutId(), context.userId(), cr.type(), cr.amount()));
        }
        return list;
    }

    private PaymentExecutionResult finalize(long paymentId,
                                            Map<PaymentMethodType, Long> componentIds,
                                            CompositionResult composition) {
        return transactionTemplate.execute(status -> switch (composition) {
            case CompositionResult.AllSucceeded a -> {
                for (ChargeOutcome.Succeeded s : a.successfulComponents()) {
                    componentRepository.markSucceeded(componentIds.get(s.type()), s.externalTransactionId());
                }
                paymentRepository.markCompleted(paymentId, PaymentStatus.SUCCEEDED, LocalDateTime.now(clock));
                yield new PaymentExecutionResult.Succeeded(paymentId, a.successfulComponents());
            }
            case CompositionResult.ConfirmedFailure cf -> {
                for (ChargeOutcome.Succeeded s : cf.successfulComponents()) {
                    componentRepository.markSucceeded(componentIds.get(s.type()), s.externalTransactionId());
                }
                componentRepository.markFailed(componentIds.get(cf.failedAt()));
                paymentRepository.markCompleted(paymentId, PaymentStatus.COMPENSATED, LocalDateTime.now(clock));
                yield new PaymentExecutionResult.Failed(paymentId, cf.reason(), cf.failedAt());
            }
            case CompositionResult.ResultPending rp -> {
                for (ChargeOutcome.Succeeded s : rp.successfulComponents()) {
                    componentRepository.markSucceeded(componentIds.get(s.type()), s.externalTransactionId());
                }
                paymentRepository.updateStatus(paymentId, PaymentStatus.RESULT_PENDING);
                yield new PaymentExecutionResult.Pending(paymentId, rp.pendingAt(), rp.pgIdempotencyKey());
            }
        });
    }
}
