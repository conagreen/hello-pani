package com.example.hellopani.booking.application;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.example.hellopani.booking.domain.Booking;
import com.example.hellopani.booking.infra.BookingRepository;
import com.example.hellopani.checkout.domain.Checkout;
import com.example.hellopani.checkout.domain.CheckoutAlreadyConsumedException;
import com.example.hellopani.checkout.domain.CheckoutExpiredException;
import com.example.hellopani.checkout.domain.CheckoutNotFoundException;
import com.example.hellopani.checkout.domain.CheckoutOwnershipMismatchException;
import com.example.hellopani.checkout.domain.CheckoutStatus;
import com.example.hellopani.checkout.infra.CheckoutRepository;
import com.example.hellopani.inventory.application.InventoryProperties;
import com.example.hellopani.inventory.domain.GateAcquireResult;
import com.example.hellopani.inventory.domain.StockGate;
import com.example.hellopani.inventory.domain.StockReserveFailedException;
import com.example.hellopani.inventory.infra.StockRepository;
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
import tools.jackson.databind.ObjectMapper;

@Service
public class BookingService {

    private final IdempotencyService idempotencyService;
    private final CheckoutRepository checkoutRepository;
    private final StockGate stockGate;
    private final StockRepository stockRepository;
    private final BookingRepository bookingRepository;
    private final PaymentRepository paymentRepository;
    private final PaymentComponentRepository paymentComponentRepository;
    private final PaymentValidator paymentValidator;
    private final PaymentService paymentService;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper;
    private final InventoryProperties inventoryProperties;
    private final Clock clock;

    public BookingService(IdempotencyService idempotencyService,
                          CheckoutRepository checkoutRepository,
                          StockGate stockGate,
                          StockRepository stockRepository,
                          BookingRepository bookingRepository,
                          PaymentRepository paymentRepository,
                          PaymentComponentRepository paymentComponentRepository,
                          PaymentValidator paymentValidator,
                          PaymentService paymentService,
                          PlatformTransactionManager transactionManager,
                          ObjectMapper objectMapper,
                          InventoryProperties inventoryProperties,
                          Clock clock) {
        this.idempotencyService = idempotencyService;
        this.checkoutRepository = checkoutRepository;
        this.stockGate = stockGate;
        this.stockRepository = stockRepository;
        this.bookingRepository = bookingRepository;
        this.paymentRepository = paymentRepository;
        this.paymentComponentRepository = paymentComponentRepository;
        this.paymentValidator = paymentValidator;
        this.paymentService = paymentService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.objectMapper = objectMapper;
        this.inventoryProperties = inventoryProperties;
        this.clock = clock;
    }

    public BookingExecutionResult handle(BookingHandleInput input) {
        IdempotencyAcquisition acquisition = idempotencyService.tryAcquire(input.checkoutId());
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

        GateAcquireResult gateResult = stockGate.tryAcquire(
                checkout.productId(), input.userId(), input.checkoutId());
        if (gateResult instanceof GateAcquireResult.Rejected rejected) {
            idempotencyService.release(input.checkoutId());
            return new BookingExecutionResult.Rejected(
                    input.checkoutId(),
                    BookingExecutionResult.RejectionCode.SOLD_OUT_OR_PROCESSING,
                    rejected.retryable(),
                    rejected.retryAfterSeconds(),
                    "sold out or processing");
        }

        ReservationOutput reservation;
        try {
            reservation = reserveInTransaction(input, checkout, requests);
        } catch (StockReserveFailedException e) {
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

        BookingExecutionResult result = finalizePostReservation(
                input, checkout, reservation, payResult);
        idempotencyService.completeWithResult(input.checkoutId(), serialize(toPayload(result)));
        return result;
    }

    private Checkout loadAndValidateCheckout(BookingHandleInput input) {
        Checkout checkout = checkoutRepository.findById(input.checkoutId())
                .orElseThrow(() -> new PreReserveValidationFailure(new BookingExecutionResult.Rejected(
                        input.checkoutId(),
                        BookingExecutionResult.RejectionCode.CHECKOUT_NOT_FOUND,
                        false, 0, "Checkout not found")));
        if (!checkout.userId().equals(input.userId())) {
            throw new PreReserveValidationFailure(new BookingExecutionResult.Rejected(
                    input.checkoutId(),
                    BookingExecutionResult.RejectionCode.CHECKOUT_USER_MISMATCH,
                    false, 0, "Checkout owner mismatch"));
        }
        if (checkout.status() != CheckoutStatus.ISSUED) {
            throw new PreReserveValidationFailure(new BookingExecutionResult.Rejected(
                    input.checkoutId(),
                    BookingExecutionResult.RejectionCode.CHECKOUT_ALREADY_CONSUMED,
                    false, 0, "Checkout already consumed"));
        }
        if (LocalDateTime.now(clock).isAfter(checkout.expiresAt())) {
            throw new PreReserveValidationFailure(new BookingExecutionResult.Rejected(
                    input.checkoutId(),
                    BookingExecutionResult.RejectionCode.CHECKOUT_EXPIRED,
                    false, 0, "Checkout expired"));
        }
        return checkout;
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
                                                           PaymentExecutionResult payResult) {
        return transactionTemplate.execute(status -> switch (payResult) {
            case PaymentExecutionResult.Succeeded s -> {
                bookingRepository.markConfirmed(reservation.bookingId(), LocalDateTime.now(clock));
                checkoutRepository.markUsed(input.checkoutId());
                yield (BookingExecutionResult) new BookingExecutionResult.Confirmed(
                        input.checkoutId(), reservation.bookingId(), reservation.paymentId());
            }
            case PaymentExecutionResult.Failed f -> {
                bookingRepository.markFailed(reservation.bookingId());
                stockRepository.increment(checkout.productId());
                stockGate.release(checkout.productId(), input.checkoutId());
                yield new BookingExecutionResult.Failed(
                        input.checkoutId(), reservation.bookingId(), reservation.paymentId(),
                        f.reason().name());
            }
            case PaymentExecutionResult.Pending p -> new BookingExecutionResult.Pending(
                    input.checkoutId(), reservation.bookingId(), reservation.paymentId(),
                    p.pgIdempotencyKey());
        });
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

    public Booking findBooking(long bookingId) {
        return bookingRepository.findById(bookingId).orElseThrow();
    }

    public Checkout findCheckout(String checkoutId) {
        return checkoutRepository.findById(checkoutId)
                .orElseThrow(() -> new CheckoutNotFoundException(checkoutId));
    }

    void throwIfMisuse(String checkoutId, String userId, Checkout checkout) {
        if (!checkout.userId().equals(userId)) {
            throw new CheckoutOwnershipMismatchException(checkoutId);
        }
        if (checkout.status() != CheckoutStatus.ISSUED) {
            throw new CheckoutAlreadyConsumedException(checkoutId);
        }
        if (LocalDateTime.now(clock).isAfter(checkout.expiresAt())) {
            throw new CheckoutExpiredException(checkoutId);
        }
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
