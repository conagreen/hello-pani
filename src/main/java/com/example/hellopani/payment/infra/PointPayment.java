package com.example.hellopani.payment.infra;

import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.example.hellopani.payment.domain.ChargeOutcome;
import com.example.hellopani.payment.domain.ChargeRequest;
import com.example.hellopani.payment.domain.FailureReason;
import com.example.hellopani.payment.domain.PaymentMethod;
import com.example.hellopani.payment.domain.PaymentMethodType;
import com.example.hellopani.point.domain.PointReason;
import com.example.hellopani.point.infra.PointLedgerRepository;
import com.example.hellopani.point.infra.PointRepository;

@Component
public class PointPayment implements PaymentMethod {

    private final PointRepository pointRepository;
    private final PointLedgerRepository pointLedgerRepository;
    private final TransactionTemplate transactionTemplate;

    public PointPayment(PointRepository pointRepository,
                        PointLedgerRepository pointLedgerRepository,
                        PlatformTransactionManager transactionManager) {
        this.pointRepository = pointRepository;
        this.pointLedgerRepository = pointLedgerRepository;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    public PaymentMethodType type() {
        return PaymentMethodType.POINT;
    }

    @Override
    public ChargeOutcome charge(ChargeRequest request) {
        try {
            return transactionTemplate.execute(status -> chargeInTransaction(request));
        } catch (InsufficientPointException e) {
            return new ChargeOutcome.ConfirmedFailure(type(), FailureReason.INSUFFICIENT_POINT);
        }
    }

    private ChargeOutcome chargeInTransaction(ChargeRequest request) {
        boolean inserted = pointLedgerRepository.tryInsert(
                request.userId(), request.checkoutId(), -request.amount(), PointReason.BOOKING_USE);
        if (!inserted) {
            return new ChargeOutcome.Succeeded(type(), syntheticReference(request));
        }
        int affected = pointRepository.decrement(request.userId(), request.amount());
        if (affected == 0) {
            throw new InsufficientPointException();
        }
        return new ChargeOutcome.Succeeded(type(), syntheticReference(request));
    }

    @Override
    public void refund(ChargeRequest request, ChargeOutcome.Succeeded prior) {
        transactionTemplate.executeWithoutResult(status -> {
            boolean inserted = pointLedgerRepository.tryInsert(
                    request.userId(), request.checkoutId(), request.amount(), PointReason.BOOKING_REFUND);
            if (!inserted) {
                return;
            }
            pointRepository.increment(request.userId(), request.amount());
        });
    }

    private static String syntheticReference(ChargeRequest request) {
        return "internal-point-" + request.checkoutId();
    }

    static final class InsufficientPointException extends RuntimeException {
        InsufficientPointException() {
            super("Insufficient point balance");
        }
    }
}
