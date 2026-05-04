package com.example.hellopani.booking.application;

import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.example.hellopani.booking.infra.BookingRepository;
import com.example.hellopani.catalog.domain.Product;
import com.example.hellopani.catalog.infra.ProductRepository;
import com.example.hellopani.checkout.application.CheckoutService;
import com.example.hellopani.checkout.domain.Checkout;
import com.example.hellopani.checkout.domain.CheckoutStatus;
import com.example.hellopani.checkout.infra.CheckoutCache;
import com.example.hellopani.checkout.infra.CheckoutRepository;
import com.example.hellopani.compensation.application.CompensationContext;
import com.example.hellopani.compensation.application.CompensationService;
import com.example.hellopani.inventory.application.InventoryProperties;
import com.example.hellopani.inventory.domain.GateAcquireResult;
import com.example.hellopani.inventory.domain.RedisUnavailableException;
import com.example.hellopani.inventory.domain.StockGate;
import com.example.hellopani.inventory.domain.StockReserveFailedException;
import com.example.hellopani.inventory.infra.StockRepository;
import com.example.hellopani.observability.AppMetrics;
import com.example.hellopani.payment.application.PaymentExecutionInput;
import com.example.hellopani.payment.application.PaymentExecutionResult;
import com.example.hellopani.payment.application.PaymentService;
import com.example.hellopani.payment.domain.AmountMismatchException;
import com.example.hellopani.payment.domain.ChargeRequest;
import com.example.hellopani.payment.domain.InvalidCompositionException;
import com.example.hellopani.payment.domain.PaymentMethodType;
import com.example.hellopani.payment.domain.PaymentValidator;
import com.example.hellopani.payment.infra.PaymentComponentRepository;
import com.example.hellopani.payment.infra.PaymentRepository;
import com.example.hellopani.point.domain.PointAccount;
import com.example.hellopani.point.infra.PointRepository;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@Service
public class BookingService {

    private final IdempotencyService idempotencyService;
    private final CheckoutCache checkoutCache;
    private final CheckoutRepository checkoutRepository;
    private final ProductRepository productRepository;
    private final PointRepository pointRepository;
    private final StockGate stockGate;
    private final StockRepository stockRepository;
    private final BookingRepository bookingRepository;
    private final PaymentRepository paymentRepository;
    private final PaymentComponentRepository paymentComponentRepository;
    private final PaymentValidator paymentValidator;
    private final PaymentService paymentService;
    private final CompensationService compensationService;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper;
    private final InventoryProperties inventoryProperties;
    private final Clock clock;
    private final AppMetrics appMetrics;

    public BookingService(IdempotencyService idempotencyService,
                          CheckoutCache checkoutCache,
                          CheckoutRepository checkoutRepository,
                          ProductRepository productRepository,
                          PointRepository pointRepository,
                          StockGate stockGate,
                          StockRepository stockRepository,
                          BookingRepository bookingRepository,
                          PaymentRepository paymentRepository,
                          PaymentComponentRepository paymentComponentRepository,
                          PaymentValidator paymentValidator,
                          PaymentService paymentService,
                          CompensationService compensationService,
                          PlatformTransactionManager transactionManager,
                          ObjectMapper objectMapper,
                          InventoryProperties inventoryProperties,
                          Clock clock,
                          AppMetrics appMetrics) {
        this.idempotencyService = idempotencyService;
        this.checkoutCache = checkoutCache;
        this.checkoutRepository = checkoutRepository;
        this.productRepository = productRepository;
        this.pointRepository = pointRepository;
        this.stockGate = stockGate;
        this.stockRepository = stockRepository;
        this.bookingRepository = bookingRepository;
        this.paymentRepository = paymentRepository;
        this.paymentComponentRepository = paymentComponentRepository;
        this.paymentValidator = paymentValidator;
        this.paymentService = paymentService;
        this.compensationService = compensationService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.objectMapper = objectMapper;
        this.inventoryProperties = inventoryProperties;
        this.clock = clock;
        this.appMetrics = appMetrics;
    }

    public BookingExecutionResult handle(BookingHandleInput input) {
        IdempotencyAcquisition acquisition;
        try {
            acquisition = idempotencyService.tryAcquire(input.checkoutId());
        } catch (RedisUnavailableException e) {
            appMetrics.http503RedisUnavailable();
            return new BookingExecutionResult.Rejected(
                    input.checkoutId(),
                    BookingExecutionResult.RejectionCode.REDIS_UNAVAILABLE,
                    true,
                    inventoryProperties.soldOutRetryAfterSeconds(),
                    "Service temporarily unavailable");
        }
        return switch (acquisition) {
            case ALREADY_DONE -> idempotencyService.findCachedResult(input.checkoutId())
                    .<BookingExecutionResult>map(json -> new BookingExecutionResult.Replayed(input.checkoutId(), json))
                    .orElseGet(() -> rejected(input.checkoutId(),
                            BookingExecutionResult.RejectionCode.DUPLICATE_REQUEST_PROCESSING,
                            true, "Cached result missing"));
            case ALREADY_PROCESSING -> rejected(input.checkoutId(),
                    BookingExecutionResult.RejectionCode.DUPLICATE_REQUEST_PROCESSING,
                    true, "Booking is already being processed");
            case ACQUIRED -> processAcquired(input);
        };
    }

    private BookingExecutionResult processAcquired(BookingHandleInput input) {
        Checkout checkout;
        List<ChargeRequest> requests;
        long totalAmount;
        try {
            checkout = loadAndValidateCheckout(input);
            totalAmount = checkout.quotedPrice();
            requests = toChargeRequests(input, totalAmount);
        } catch (PreReserveValidationFailure e) {
            idempotencyService.release(input.checkoutId());
            return e.result;
        }

        GateAcquireResult gateResult;
        try {
            gateResult = stockGate.tryAcquire(checkout.productId(), input.userId(), input.checkoutId());
        } catch (RedisUnavailableException e) {
            appMetrics.http503RedisUnavailable();
            idempotencyService.release(input.checkoutId());
            return new BookingExecutionResult.Rejected(
                    input.checkoutId(),
                    BookingExecutionResult.RejectionCode.REDIS_UNAVAILABLE,
                    true,
                    inventoryProperties.soldOutRetryAfterSeconds(),
                    "Service temporarily unavailable");
        }
        if (gateResult instanceof GateAcquireResult.Rejected rejected) {
            appMetrics.redisGateRejected();
            idempotencyService.release(input.checkoutId());
            return new BookingExecutionResult.Rejected(
                    input.checkoutId(),
                    BookingExecutionResult.RejectionCode.SOLD_OUT_OR_PROCESSING,
                    rejected.retryable(),
                    rejected.retryAfterSeconds(),
                    "sold out or processing");
        }
        appMetrics.redisGateSuccess();

        ReservationOutput reservation;
        try {
            reservation = reserveInTransaction(input, checkout, requests);
            appMetrics.dbStockReserveSuccess();
        } catch (StockReserveFailedException e) {
            appMetrics.dbStockReserveFailure();
            stockGate.release(checkout.productId(), input.checkoutId());
            idempotencyService.release(input.checkoutId());
            return new BookingExecutionResult.Rejected(
                    input.checkoutId(),
                    BookingExecutionResult.RejectionCode.SOLD_OUT_OR_PROCESSING,
                    true,
                    inventoryProperties.soldOutRetryAfterSeconds(),
                    "stock reserve failed at DB");
        }

        PaymentExecutionResult payResult = paymentService.execute(new PaymentExecutionInput(
                reservation.paymentId(), totalAmount, requests, reservation.componentIds()));

        long pointRefundAmount = requests.stream()
                .filter(r -> r.type() == PaymentMethodType.POINT)
                .mapToLong(ChargeRequest::amount)
                .findFirst()
                .orElse(0L);
        BookingExecutionResult result = finalizePostReservation(
                input, checkout, reservation, payResult, pointRefundAmount);
        idempotencyService.completeWithResult(input.checkoutId(), serialize(toPayload(result)));
        return result;
    }

    private Checkout loadAndValidateCheckout(BookingHandleInput input) {
        // 1. Redis cache에서 userId 조회. 없으면 만료(TTL) 또는 미발급. 둘 다 사용자에겐 같은 의미.
        String cachedUserId = checkoutCache.findUserId(input.checkoutId())
                .orElseThrow(() -> new PreReserveValidationFailure(new BookingExecutionResult.Rejected(
                        input.checkoutId(),
                        BookingExecutionResult.RejectionCode.CHECKOUT_EXPIRED,
                        false, 0, "Checkout not found or expired")));

        // 2. 사용자 도용 차단 — Redis 매핑된 userId와 X-User-Id 일치해야 한다.
        if (!cachedUserId.equals(input.userId())) {
            throw new PreReserveValidationFailure(new BookingExecutionResult.Rejected(
                    input.checkoutId(),
                    BookingExecutionResult.RejectionCode.CHECKOUT_USER_MISMATCH,
                    false, 0, "Checkout owner mismatch"));
        }

        // 3. 이미 USED된 checkoutId는 booking row의 UNIQUE 제약 / idempotency가 차단한다.
        //    DB row가 존재하면 (= 이미 booking 시점에 INSERT됨) 그것 자체가 USED 신호.
        Optional<Checkout> persisted = checkoutRepository.findById(input.checkoutId());
        if (persisted.isPresent() && persisted.get().status() != CheckoutStatus.ISSUED) {
            throw new PreReserveValidationFailure(new BookingExecutionResult.Rejected(
                    input.checkoutId(),
                    BookingExecutionResult.RejectionCode.CHECKOUT_ALREADY_CONSUMED,
                    false, 0, "Checkout already consumed"));
        }

        // 4. 가격 / 포인트는 POST 시점에 product / point_account를 재조회해 캡처한다.
        //    GET 시점 캡처가 아니므로 가격이 GET~POST 사이 변동되면 새 가격 기준으로 검증된다.
        Product product = productRepository.findById(input.productId())
                .orElseThrow(() -> new PreReserveValidationFailure(new BookingExecutionResult.Rejected(
                        input.checkoutId(),
                        BookingExecutionResult.RejectionCode.CHECKOUT_NOT_FOUND,
                        false, 0, "Product not found")));
        long availablePoint = pointRepository.findByUserId(input.userId())
                .map(PointAccount::balance).orElse(0L);

        LocalDateTime now = LocalDateTime.now(clock);
        // ISSUED 상태로 in-memory snapshot. reserveInTransaction에서 DB INSERT 시 그대로 사용.
        return new Checkout(
                input.checkoutId(), input.userId(), product.productId(),
                product.price(), availablePoint, CheckoutStatus.ISSUED,
                now.plus(CheckoutService.EXPIRY_DURATION), now);
    }

    private List<ChargeRequest> toChargeRequests(BookingHandleInput input, long totalAmount) {
        List<ChargeRequest> requests = new ArrayList<>(input.components().size());
        for (BookingHandleInput.ComponentInput c : input.components()) {
            requests.add(new ChargeRequest(input.checkoutId(), input.userId(), c.type(), c.amount()));
        }
        try {
            paymentValidator.validate(requests, totalAmount);
        } catch (InvalidCompositionException e) {
            throw new PreReserveValidationFailure(new BookingExecutionResult.Rejected(
                    input.checkoutId(),
                    BookingExecutionResult.RejectionCode.INVALID_COMPOSITION,
                    false, 0, e.getMessage()));
        } catch (AmountMismatchException e) {
            throw new PreReserveValidationFailure(new BookingExecutionResult.Rejected(
                    input.checkoutId(),
                    BookingExecutionResult.RejectionCode.AMOUNT_MISMATCH,
                    false, 0, e.getMessage()));
        }
        return requests;
    }

    private ReservationOutput reserveInTransaction(BookingHandleInput input,
                                                   Checkout checkout,
                                                   List<ChargeRequest> requests) {
        return transactionTemplate.execute(status -> {
            int decremented = stockRepository.decrement(checkout.productId());
            if (decremented == 0) {
                throw new StockReserveFailedException(checkout.productId());
            }
            // checkout row를 DB에 영속화 — 게이트 통과자만 도달하므로 이 시점에야 INSERT한다.
            // FK 정합성: booking / payment_component가 checkout을 FK로 참조한다.
            checkoutRepository.insert(checkout);
            long bookingId = bookingRepository.insertPending(
                    input.checkoutId(), input.userId(), checkout.productId(), checkout.quotedPrice());
            long paymentId = paymentRepository.insertProcessing(
                    input.checkoutId(), bookingId, input.userId(),
                    checkout.quotedPrice(), input.checkoutId());
            Map<PaymentMethodType, Long> componentIds = new EnumMap<>(PaymentMethodType.class);
            for (ChargeRequest req : requests) {
                long componentId = paymentComponentRepository.insertPending(
                        paymentId, req.type(), req.amount());
                componentIds.put(req.type(), componentId);
            }
            return new ReservationOutput(bookingId, paymentId, componentIds);
        });
    }

    private BookingExecutionResult finalizePostReservation(BookingHandleInput input,
                                                           Checkout checkout,
                                                           ReservationOutput reservation,
                                                           PaymentExecutionResult payResult,
                                                           long pointRefundAmount) {
        return switch (payResult) {
            case PaymentExecutionResult.Succeeded s -> {
                BookingExecutionResult confirmed = transactionTemplate.execute(status -> {
                    bookingRepository.markConfirmed(reservation.bookingId(), LocalDateTime.now(clock));
                    checkoutRepository.markUsed(input.checkoutId());
                    return (BookingExecutionResult) new BookingExecutionResult.Confirmed(
                            input.checkoutId(), reservation.bookingId(), reservation.paymentId());
                });
                appMetrics.bookingConfirmed();
                yield confirmed;
            }
            case PaymentExecutionResult.Failed f -> {
                appMetrics.paymentFailure(f.reason());
                // Composer가 이미 PointPayment.refund를 역순 보상으로 호출했더라도,
                // CompensationService 호출 시 ledger UNIQUE로 멱등 처리되고 POINT_REFUNDED step 기록까지 일관.
                compensationService.compensate(new CompensationContext(
                        input.checkoutId(), input.userId(), checkout.productId(),
                        pointRefundAmount, reservation.paymentId()));
                transactionTemplate.executeWithoutResult(status ->
                        bookingRepository.markFailed(reservation.bookingId()));
                yield new BookingExecutionResult.Failed(
                        input.checkoutId(), reservation.bookingId(), reservation.paymentId(),
                        f.reason().name());
            }
            case PaymentExecutionResult.Pending p -> new BookingExecutionResult.Pending(
                    input.checkoutId(), reservation.bookingId(), reservation.paymentId(),
                    p.pgIdempotencyKey());
        };
    }


    private BookingResultPayload toPayload(BookingExecutionResult result) {
        return switch (result) {
            case BookingExecutionResult.Confirmed c -> new BookingResultPayload(
                    c.checkoutId(), BookingResultPayload.STATUS_CONFIRMED,
                    c.bookingId(), c.paymentId(), null);
            case BookingExecutionResult.Failed f -> new BookingResultPayload(
                    f.checkoutId(), BookingResultPayload.STATUS_FAILED,
                    f.bookingId(), f.paymentId(), f.reason());
            case BookingExecutionResult.Pending p -> new BookingResultPayload(
                    p.checkoutId(), BookingResultPayload.STATUS_PENDING,
                    p.bookingId(), p.paymentId(), "Payment result is being verified");
            case BookingExecutionResult.Rejected r -> new BookingResultPayload(
                    r.checkoutId(), BookingResultPayload.STATUS_FAILED,
                    null, null, r.message());
            case BookingExecutionResult.Replayed r -> new BookingResultPayload(
                    r.checkoutId(), BookingResultPayload.STATUS_FAILED, null, null, "replayed");
        };
    }

    private String serialize(BookingResultPayload payload) {
        return objectMapper.writeValueAsString(payload);
    }

    private BookingExecutionResult rejected(String checkoutId,
                                            BookingExecutionResult.RejectionCode code,
                                            boolean retryable,
                                            String message) {
        return new BookingExecutionResult.Rejected(checkoutId, code, retryable, 0, message);
    }

    private record ReservationOutput(long bookingId, long paymentId, Map<PaymentMethodType, Long> componentIds) {
    }

    private static final class PreReserveValidationFailure extends RuntimeException {
        final BookingExecutionResult.Rejected result;

        PreReserveValidationFailure(BookingExecutionResult.Rejected result) {
            this.result = result;
        }
    }
}
