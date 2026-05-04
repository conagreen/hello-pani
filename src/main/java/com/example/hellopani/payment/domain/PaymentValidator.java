package com.example.hellopani.payment.domain;

import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class PaymentValidator {

    public void validate(List<ChargeRequest> requests, long expectedTotal) {
        if (requests == null || requests.isEmpty()) {
            throw new InvalidCompositionException("At least one payment component is required");
        }

        boolean hasCard = requests.stream().anyMatch(r -> r.type() == PaymentMethodType.CARD);
        boolean hasYPay = requests.stream().anyMatch(r -> r.type() == PaymentMethodType.Y_PAY);
        if (hasCard && hasYPay) {
            throw new InvalidCompositionException("CARD + Y_PAY combination is not allowed");
        }

        long sum = 0L;
        for (ChargeRequest req : requests) {
            if (req.amount() <= 0) {
                throw new InvalidCompositionException("Each component amount must be positive");
            }
            sum += req.amount();
        }
        if (sum != expectedTotal) {
            throw new AmountMismatchException(
                    "Sum mismatch: expected=" + expectedTotal + ", got=" + sum);
        }
    }
}
