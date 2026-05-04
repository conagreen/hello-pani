package com.example.hellopani.booking.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.example.hellopani.booking.domain.Booking;
import com.example.hellopani.booking.domain.BookingStatus;
import com.example.hellopani.booking.infra.BookingRepository;
import com.example.hellopani.checkout.domain.Checkout;
import com.example.hellopani.checkout.infra.CheckoutRepository;
import com.example.hellopani.compensation.application.CompensationContext;
import com.example.hellopani.compensation.application.CompensationService;
import com.example.hellopani.payment.domain.Payment;
import com.example.hellopani.payment.domain.PaymentComponent;
import com.example.hellopani.payment.domain.PaymentComponentStatus;
import com.example.hellopani.payment.domain.PaymentMethodType;
import com.example.hellopani.payment.domain.PaymentStatus;
import com.example.hellopani.payment.infra.PaymentComponentRepository;
import com.example.hellopani.payment.infra.PaymentRepository;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Component
public class ExpiryCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(ExpiryCleanupJob.class);

    private final CheckoutRepository checkoutRepository;
    private final BookingRepository bookingRepository;
    private final PaymentRepository paymentRepository;
    private final PaymentComponentRepository componentRepository;
    private final CompensationService compensationService;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    public ExpiryCleanupJob(CheckoutRepository checkoutRepository,
                            BookingRepository bookingRepository,
                            PaymentRepository paymentRepository,
                            PaymentComponentRepository componentRepository,
                            CompensationService compensationService,
                            PlatformTransactionManager transactionManager,
                            Clock clock) {
        this.checkoutRepository = checkoutRepository;
        this.bookingRepository = bookingRepository;
        this.paymentRepository = paymentRepository;
        this.componentRepository = componentRepository;
        this.compensationService = compensationService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${app.booking.expiry-cleanup-interval-ms:60000}")
    public void cleanupExpired() {
        List<Checkout> expired = checkoutRepository.findIssuedExpiredBefore(LocalDateTime.now(clock));
        for (Checkout checkout : expired) {
            try {
                cleanupOne(checkout);
            } catch (Exception e) {
                log.error("Failed to cleanup expired checkoutId={}", checkout.checkoutId(), e);
            }
        }
    }

    void cleanupOne(Checkout checkout) {
        Optional<Booking> bookingOpt = bookingRepository.findByCheckoutId(checkout.checkoutId());
        if (bookingOpt.isEmpty()) {
            checkoutRepository.markExpired(checkout.checkoutId());
            return;
        }

        Booking booking = bookingOpt.get();
        if (booking.status() == BookingStatus.CONFIRMED) {
            // 정상 예약된 booking. 정리 잡이 변경하지 않는다 (SUCCEEDED 보호 정신).
            return;
        }
        if (booking.status() == BookingStatus.FAILED) {
            checkoutRepository.markExpired(checkout.checkoutId());
            return;
        }

        // PENDING_PAYMENT — payment 상태 확인
        Optional<Payment> paymentOpt = paymentRepository.findByCheckoutId(checkout.checkoutId());
        if (paymentOpt.isEmpty()) {
            // booking은 있는데 payment 없음 — 이상 상태. checkout만 만료 마킹.
            checkoutRepository.markExpired(checkout.checkoutId());
            return;
        }

        Payment payment = paymentOpt.get();
        switch (payment.status()) {
            case SUCCEEDED -> {
                // 절대 건드리지 않음. booking은 PENDING_PAYMENT지만 payment SUCCEEDED인 sync 어긋남이라
                // 운영자 확인이 필요한 영역.
                log.warn("Inconsistent: booking PENDING but payment SUCCEEDED for checkoutId={}",
                        checkout.checkoutId());
            }
            case RESULT_PENDING -> {
                // PaymentResolutionJob에 위임. 정리 잡은 건드리지 않음.
            }
            case PROCESSING -> {
                // 비정상 장기 PROCESSING — FAILED로 마킹한 뒤 보상 사이클로 위임.
                markFailedThenCompensate(checkout, booking, payment);
            }
            case FAILED, COMPENSATING -> {
                // 보상이 미완 상태로 남았을 수 있다 (서버 크래시 / 부분 실행).
                // CompensationService는 step 기록 기준으로 멱등하므로 다시 호출해 사이클을 닫는다.
                resumeCompensation(checkout, booking, payment);
            }
            case COMPENSATED, REFUND_FAILED -> {
                // 보상 사이클 종료. checkout만 만료 마킹.
                checkoutRepository.markExpired(checkout.checkoutId());
            }
        }
    }

    private void markFailedThenCompensate(Checkout checkout, Booking booking, Payment payment) {
        transactionTemplate.executeWithoutResult(status -> {
            for (PaymentComponent c : componentRepository.findByPaymentId(payment.paymentId())) {
                if (c.status() == PaymentComponentStatus.PENDING) {
                    componentRepository.markFailed(c.paymentComponentId());
                }
            }
            paymentRepository.markCompleted(payment.paymentId(), PaymentStatus.FAILED, LocalDateTime.now(clock));
        });
        resumeCompensation(checkout, booking, payment);
    }

    private void resumeCompensation(Checkout checkout, Booking booking, Payment payment) {
        long pointAmount = pointAmountToRefund(payment);
        compensationService.compensate(new CompensationContext(
                checkout.checkoutId(), checkout.userId(), checkout.productId(),
                pointAmount, payment.paymentId()));
        transactionTemplate.executeWithoutResult(status -> {
            if (booking.status() == BookingStatus.PENDING_PAYMENT) {
                bookingRepository.markFailed(booking.bookingId());
            }
            checkoutRepository.markExpired(checkout.checkoutId());
        });
    }

    private long pointAmountToRefund(Payment payment) {
        return componentRepository.findByPaymentId(payment.paymentId()).stream()
                .filter(c -> c.method() == PaymentMethodType.POINT)
                .filter(c -> c.status() == PaymentComponentStatus.SUCCEEDED)
                .mapToLong(PaymentComponent::amount)
                .findFirst()
                .orElse(0L);
    }
}
