package com.example.hellopani.payment.infra;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import com.example.hellopani.payment.domain.ChargeOutcome;
import com.example.hellopani.payment.domain.ChargeRequest;
import com.example.hellopani.payment.domain.FailureReason;
import com.example.hellopani.payment.domain.PaymentMethodType;
import com.example.hellopani.payment.domain.PgChargeRequest;
import com.example.hellopani.payment.domain.PgChargeResult;
import com.example.hellopani.payment.domain.PgClient;
import com.example.hellopani.payment.domain.PgPaymentInstrument;

import static org.assertj.core.api.Assertions.assertThat;

class CardPaymentTest {

    @Test
    void mapsApprovedToSucceeded() {
        StubPgClient pg = new StubPgClient(new PgChargeResult.Approved("ext-1"));
        CardPayment card = new CardPayment(pg);

        ChargeOutcome outcome = card.charge(req(50_000L));

        assertThat(outcome).isInstanceOf(ChargeOutcome.Succeeded.class);
        assertThat(((ChargeOutcome.Succeeded) outcome).externalTransactionId()).isEqualTo("ext-1");
        assertThat(pg.lastInstrument).isEqualTo(PgPaymentInstrument.CARD);
    }

    @Test
    void mapsDeclinedToConfirmedFailure() {
        StubPgClient pg = new StubPgClient(new PgChargeResult.Declined(FailureReason.CARD_DECLINED));
        CardPayment card = new CardPayment(pg);

        ChargeOutcome outcome = card.charge(req(50_000L));

        ChargeOutcome.ConfirmedFailure failure = (ChargeOutcome.ConfirmedFailure) outcome;
        assertThat(failure.reason()).isEqualTo(FailureReason.CARD_DECLINED);
        assertThat(failure.type()).isEqualTo(PaymentMethodType.CARD);
    }

    @Test
    void mapsPendingToResultPending() {
        StubPgClient pg = new StubPgClient(new PgChargeResult.Pending("ck-1"));
        CardPayment card = new CardPayment(pg);

        ChargeOutcome outcome = card.charge(req(50_000L));

        ChargeOutcome.ResultPending pending = (ChargeOutcome.ResultPending) outcome;
        assertThat(pending.pgIdempotencyKey()).isEqualTo("ck-1");
    }

    @Test
    void refundDelegatesPgRefundWithCheckoutIdAsKey() {
        StubPgClient pg = new StubPgClient(new PgChargeResult.Approved("ext-1"));
        CardPayment card = new CardPayment(pg);
        ChargeRequest request = req(50_000L);

        card.refund(request, new ChargeOutcome.Succeeded(PaymentMethodType.CARD, "ext-1"));

        assertThat(pg.refunded).containsExactly(request.checkoutId());
    }

    private static ChargeRequest req(long amount) {
        return new ChargeRequest("ck-1", "u-1", PaymentMethodType.CARD, amount);
    }

    static final class StubPgClient implements PgClient {
        private final PgChargeResult result;
        PgPaymentInstrument lastInstrument;
        final List<String> refunded = new ArrayList<>();

        StubPgClient(PgChargeResult result) {
            this.result = result;
        }

        @Override
        public PgChargeResult charge(PgChargeRequest request) {
            this.lastInstrument = request.instrument();
            return result;
        }

        @Override
        public PgChargeResult lookupResult(String pgIdempotencyKey) {
            return new PgChargeResult.NotFound();
        }

        @Override
        public void refund(String pgIdempotencyKey) {
            refunded.add(pgIdempotencyKey);
        }
    }
}
