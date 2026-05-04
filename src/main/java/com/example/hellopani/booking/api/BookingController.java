package com.example.hellopani.booking.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import com.example.hellopani.booking.application.BookingExecutionResult;
import com.example.hellopani.booking.application.BookingHandleInput;
import com.example.hellopani.booking.application.BookingResultPayload;
import com.example.hellopani.booking.application.BookingService;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

@RestController
public class BookingController {

    private final BookingService bookingService;
    private final ObjectMapper objectMapper;

    public BookingController(BookingService bookingService, ObjectMapper objectMapper) {
        this.bookingService = bookingService;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/bookings")
    public ResponseEntity<?> postBooking(
            @RequestHeader("X-User-Id") String userId,
            @RequestBody BookingRequest request) {
        BookingHandleInput input = toInput(userId, request);
        BookingExecutionResult result = bookingService.handle(input);
        return toResponse(result);
    }

    private BookingHandleInput toInput(String userId, BookingRequest request) {
        List<BookingHandleInput.ComponentInput> components = new ArrayList<>(request.payments().size());
        for (BookingRequest.PaymentInput p : request.payments()) {
            components.add(new BookingHandleInput.ComponentInput(p.method(), p.amount()));
        }
        return new BookingHandleInput(request.checkoutId(), userId, components);
    }

    private ResponseEntity<?> toResponse(BookingExecutionResult result) {
        return switch (result) {
            case BookingExecutionResult.Confirmed c -> ResponseEntity.ok(new BookingResultPayload(
                    c.checkoutId(), BookingResultPayload.STATUS_CONFIRMED,
                    c.bookingId(), c.paymentId(), null));
            case BookingExecutionResult.Failed f -> ResponseEntity.ok(new BookingResultPayload(
                    f.checkoutId(), BookingResultPayload.STATUS_FAILED,
                    f.bookingId(), f.paymentId(), f.reason()));
            case BookingExecutionResult.Pending p -> ResponseEntity.ok(new BookingResultPayload(
                    p.checkoutId(), BookingResultPayload.STATUS_PENDING,
                    p.bookingId(), p.paymentId(), "Payment result is being verified"));
            case BookingExecutionResult.Rejected r -> {
                ResponseEntity.BodyBuilder builder = ResponseEntity.status(toHttpStatus(r.code()));
                if (r.retryable() && r.retryAfterSeconds() > 0) {
                    builder.header("Retry-After", Integer.toString(r.retryAfterSeconds()));
                }
                yield builder.body(new RejectionResponse(
                        r.code().name(), r.retryable(), r.retryAfterSeconds(), r.message()));
            }
            case BookingExecutionResult.Replayed r -> {
                BookingResultPayload cached = objectMapper.readValue(r.cachedJson(), BookingResultPayload.class);
                yield ResponseEntity.ok(cached);
            }
        };
    }

    private HttpStatus toHttpStatus(BookingExecutionResult.RejectionCode code) {
        return switch (code) {
            case CHECKOUT_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case CHECKOUT_USER_MISMATCH -> HttpStatus.FORBIDDEN;
            case CHECKOUT_EXPIRED, INVALID_COMPOSITION, AMOUNT_MISMATCH -> HttpStatus.BAD_REQUEST;
            case CHECKOUT_ALREADY_CONSUMED, SOLD_OUT_OR_PROCESSING, DUPLICATE_REQUEST_PROCESSING -> HttpStatus.CONFLICT;
            case REDIS_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
        };
    }
}
