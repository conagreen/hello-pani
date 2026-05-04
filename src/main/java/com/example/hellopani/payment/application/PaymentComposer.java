package com.example.hellopani.payment.application;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import com.example.hellopani.payment.domain.ChargeOutcome;
import com.example.hellopani.payment.domain.ChargeRequest;
import com.example.hellopani.payment.domain.PaymentMethod;
import com.example.hellopani.payment.domain.PaymentMethodType;
import com.example.hellopani.payment.domain.PaymentValidator;

@Component
public class PaymentComposer {

    private final PaymentValidator validator;
    private final Map<PaymentMethodType, PaymentMethod> methods;

    public PaymentComposer(PaymentValidator validator, List<PaymentMethod> methods) {
        this.validator = validator;
        Map<PaymentMethodType, PaymentMethod> indexed = new EnumMap<>(PaymentMethodType.class);
        for (PaymentMethod method : methods) {
            PaymentMethod existing = indexed.put(method.type(), method);
            if (existing != null) {
                throw new IllegalStateException(
                        "Duplicate PaymentMethod for type " + method.type());
            }
        }
        this.methods = indexed;
    }

    public CompositionResult compose(List<ChargeRequest> requests, long expectedTotal) {
        validator.validate(requests, expectedTotal);

        List<ChargeOutcome.Succeeded> succeeded = new ArrayList<>();
        List<ChargeRequest> succeededRequests = new ArrayList<>();

        for (ChargeRequest request : requests) {
            PaymentMethod method = methodFor(request.type());
            ChargeOutcome outcome = method.charge(request);

            switch (outcome) {
                case ChargeOutcome.Succeeded s -> {
                    succeeded.add(s);
                    succeededRequests.add(request);
                }
                case ChargeOutcome.ConfirmedFailure cf -> {
                    refundReverse(succeeded, succeededRequests);
                    return new CompositionResult.ConfirmedFailure(
                            cf.reason(), cf.type(), List.copyOf(succeeded));
                }
                case ChargeOutcome.ResultPending rp -> {
                    return new CompositionResult.ResultPending(
                            rp.type(), rp.pgIdempotencyKey(), List.copyOf(succeeded));
                }
            }
        }

        return new CompositionResult.AllSucceeded(List.copyOf(succeeded));
    }

    private void refundReverse(List<ChargeOutcome.Succeeded> succeeded,
                               List<ChargeRequest> succeededRequests) {
        for (int i = succeeded.size() - 1; i >= 0; i--) {
            ChargeOutcome.Succeeded prior = succeeded.get(i);
            ChargeRequest priorRequest = succeededRequests.get(i);
            methodFor(prior.type()).refund(priorRequest, prior);
        }
    }

    private PaymentMethod methodFor(PaymentMethodType type) {
        PaymentMethod method = methods.get(type);
        if (method == null) {
            throw new IllegalStateException("No PaymentMethod registered for type " + type);
        }
        return method;
    }
}
