package com.example.hellopani.payment.infra;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import com.example.hellopani.payment.domain.FailureReason;
import com.example.hellopani.payment.domain.PgChargeRequest;
import com.example.hellopani.payment.domain.PgChargeResult;
import com.example.hellopani.payment.domain.PgPaymentInstrument;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("FakePgClient — 결정적 테스트를 위한 magic amount 트리거")
class FakePgClientTest {

    private final FakePgClient client = new FakePgClient();

    @Test
    @DisplayName("일반 amount는 Approved와 외부 거래번호를 반환한다")
    void normalAmountReturnsApproved() {
        PgChargeResult result = client.charge(req("ck-1", 100_000L));

        assertThat(result).isInstanceOf(PgChargeResult.Approved.class);
    }

    @Test
    @DisplayName("TRIGGER_LIMIT_EXCEEDED amount는 LIMIT_EXCEEDED Declined를 반환한다")
    void triggerLimitExceededReturnsDeclined() {
        PgChargeResult result = client.charge(req("ck-2", FakePgClient.TRIGGER_LIMIT_EXCEEDED));

        assertThat(result).isInstanceOf(PgChargeResult.Declined.class);
        assertThat(((PgChargeResult.Declined) result).reason()).isEqualTo(FailureReason.LIMIT_EXCEEDED);
    }

    @Test
    @DisplayName("TRIGGER_CARD_DECLINED amount는 CARD_DECLINED Declined를 반환한다")
    void triggerCardDeclinedReturnsDeclined() {
        PgChargeResult result = client.charge(req("ck-3", FakePgClient.TRIGGER_CARD_DECLINED));

        assertThat(((PgChargeResult.Declined) result).reason()).isEqualTo(FailureReason.CARD_DECLINED);
    }

    @Test
    @DisplayName("TRIGGER_RESULT_PENDING amount는 Pending과 함께 PG 멱등키를 반환한다")
    void triggerResultPendingReturnsPending() {
        PgChargeResult result = client.charge(req("ck-4", FakePgClient.TRIGGER_RESULT_PENDING));

        assertThat(result).isInstanceOf(PgChargeResult.Pending.class);
        assertThat(((PgChargeResult.Pending) result).pgIdempotencyKey()).isEqualTo("ck-4");
    }

    @Test
    @DisplayName("이전 charge의 결과는 lookupResult로 같은 멱등키로 다시 조회된다")
    void lookupReturnsCachedChargeResult() {
        client.charge(req("ck-5", 100_000L));

        PgChargeResult lookup = client.lookupResult("ck-5");

        assertThat(lookup).isInstanceOf(PgChargeResult.Approved.class);
    }

    @Test
    @DisplayName("기록되지 않은 멱등키는 NotFound를 반환한다 (PG 결과 미수신 상태)")
    void lookupReturnsNotFoundForUnknownKey() {
        PgChargeResult lookup = client.lookupResult("ck-unknown");

        assertThat(lookup).isInstanceOf(PgChargeResult.NotFound.class);
    }

    @Test
    @DisplayName("refund 호출은 캐시된 결과를 제거해 lookupResult가 NotFound로 바뀌게 한다")
    void refundClearsCachedResult() {
        client.charge(req("ck-6", 100_000L));
        client.refund("ck-6");

        assertThat(client.lookupResult("ck-6")).isInstanceOf(PgChargeResult.NotFound.class);
    }

    private static PgChargeRequest req(String key, long amount) {
        return new PgChargeRequest(key, "u", amount, PgPaymentInstrument.CARD);
    }
}
