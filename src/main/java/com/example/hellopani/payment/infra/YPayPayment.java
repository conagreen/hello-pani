package com.example.hellopani.payment.infra;

import org.springframework.stereotype.Component;
import com.example.hellopani.payment.domain.ChargeOutcome;
import com.example.hellopani.payment.domain.ChargeRequest;
import com.example.hellopani.payment.domain.PaymentMethod;
import com.example.hellopani.payment.domain.PaymentMethodType;
import com.example.hellopani.payment.domain.PgChargeRequest;
import com.example.hellopani.payment.domain.PgChargeResult;
import com.example.hellopani.payment.domain.PgClient;
import com.example.hellopani.payment.domain.PgPaymentInstrument;

@Component
public class YPayPayment implements PaymentMethod {

    private final PgClient pgClient;

    public YPayPayment(PgClient pgClient) {
        this.pgClient = pgClient;
    }

    @Override
    public PaymentMethodType type() {
        return PaymentMethodType.Y_PAY;
    }

    @Override
    public ChargeOutcome charge(ChargeRequest request) {
        PgChargeResult result = pgClient.charge(new PgChargeRequest(
                request.checkoutId(), request.userId(), request.amount(), PgPaymentInstrument.Y_PAY));
        return PgChargeResultMapper.toOutcome(type(), result);
    }

    @Override
    public void refund(ChargeRequest request, ChargeOutcome.Succeeded prior) {
        pgClient.refund(request.checkoutId());
    }
}
