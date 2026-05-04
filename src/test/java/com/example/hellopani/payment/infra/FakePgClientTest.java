package com.example.hellopani.payment.infra;

import org.junit.jupiter.api.Test;
import com.example.hellopani.payment.domain.FailureReason;
import com.example.hellopani.payment.domain.PgChargeRequest;
import com.example.hellopani.payment.domain.PgChargeResult;
import com.example.hellopani.payment.domain.PgPaymentInstrument;

import static org.assertj.core.api.Assertions.assertThat;

class FakePgClientTest {

    private final FakePgClient client = new FakePgClient();

    @Test
    void normalAmountReturnsApproved() {
        PgChargeResult result = client.charge(req("ck-1", 100_000L));

        assertThat(result).isInstanceOf(PgChargeResult.Approved.class);
    }

    @Test
    void triggerLimitExceededReturnsDeclined() {
        PgChargeResult result = client.charge(req("ck-2", FakePgClient.TRIGGER_LIMIT_EXCEEDED));

        assertThat(result).isInstanceOf(PgChargeResult.Declined.class);
        assertThat(((PgChargeResult.Declined) result).reason()).isEqualTo(FailureReason.LIMIT_EXCEEDED);
    }

    @Test
    void triggerCardDeclinedReturnsDeclined() {
        PgChargeResult result = client.charge(req("ck-3", FakePgClient.TRIGGER_CARD_DECLINED));

        assertThat(((PgChargeResult.Declined) result).reason()).isEqualTo(FailureReason.CARD_DECLINED);
    }

    @Test
    void triggerResultPendingReturnsPending() {
        PgChargeResult result = client.charge(req("ck-4", FakePgClient.TRIGGER_RESULT_PENDING));

        assertThat(result).isInstanceOf(PgChargeResult.Pending.class);
        assertThat(((PgChargeResult.Pending) result).pgIdempotencyKey()).isEqualTo("ck-4");
    }

    @Test
    void lookupReturnsCachedChargeResult() {
        client.charge(req("ck-5", 100_000L));

        PgChargeResult lookup = client.lookupResult("ck-5");

        assertThat(lookup).isInstanceOf(PgChargeResult.Approved.class);
    }

    @Test
    void lookupReturnsNotFoundForUnknownKey() {
        PgChargeResult lookup = client.lookupResult("ck-unknown");

        assertThat(lookup).isInstanceOf(PgChargeResult.NotFound.class);
    }

    @Test
    void refundClearsCachedResult() {
        client.charge(req("ck-6", 100_000L));
        client.refund("ck-6");

        assertThat(client.lookupResult("ck-6")).isInstanceOf(PgChargeResult.NotFound.class);
    }

    private static PgChargeRequest req(String key, long amount) {
        return new PgChargeRequest(key, "u", amount, PgPaymentInstrument.CARD);
    }
}
