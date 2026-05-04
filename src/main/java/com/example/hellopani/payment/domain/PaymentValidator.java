package com.example.hellopani.payment.domain;

import java.util.EnumSet;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class PaymentValidator {

    public void validate(List<ChargeRequest> requests, long expectedTotal) {
        if (requests == null || requests.isEmpty()) {
            throw new InvalidCompositionException("At least one payment component is required");
        }

        // BookingService가 component id를 EnumMap<PaymentMethodType, Long>로 모으므로 같은 타입이
        // 두 번 들어오면 앞 component id가 덮인다. 그 결과 component 상태 갱신이 어긋날 수 있어
        // 결제 수단별 최대 1개로 제한한다.
        EnumSet<PaymentMethodType> seen = EnumSet.noneOf(PaymentMethodType.class);
        for (ChargeRequest req : requests) {
            if (!seen.add(req.type())) {
                throw new InvalidCompositionException(
                        "Duplicate payment method is not allowed: " + req.type());
            }
        }

        if (seen.contains(PaymentMethodType.CARD) && seen.contains(PaymentMethodType.Y_PAY)) {
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
