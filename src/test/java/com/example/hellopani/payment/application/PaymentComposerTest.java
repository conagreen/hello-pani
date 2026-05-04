package com.example.hellopani.payment.application;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import com.example.hellopani.payment.domain.ChargeOutcome;
import com.example.hellopani.payment.domain.ChargeRequest;
import com.example.hellopani.payment.domain.FailureReason;
import com.example.hellopani.payment.domain.InvalidCompositionException;
import com.example.hellopani.payment.domain.PaymentMethod;
import com.example.hellopani.payment.domain.PaymentMethodType;
import com.example.hellopani.payment.domain.PaymentValidator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("PaymentComposer — 순차 charge / 역순 refund / RESULT_PENDING 분기")
class PaymentComposerTest {

    private final PaymentValidator validator = new PaymentValidator();

    @Test
    @DisplayName("모든 component가 성공하면 AllSucceeded 반환, refund는 호출되지 않는다")
    void runsAllComponentsInOrderWhenAllSucceed() {
        StubMethod point = StubMethod.succeeding(PaymentMethodType.POINT, "ext-point");
        StubMethod card = StubMethod.succeeding(PaymentMethodType.CARD, "ext-card");
        PaymentComposer composer = new PaymentComposer(validator, List.of(point, card));

        CompositionResult result = composer.compose(List.of(
                req(PaymentMethodType.POINT, 50_000L),
                req(PaymentMethodType.CARD, 100_000L)
        ), 150_000L);

        assertThat(result).isInstanceOf(CompositionResult.AllSucceeded.class);
        assertThat(result.successfulComponents()).hasSize(2);
        assertThat(point.chargeCalls).isEqualTo(1);
        assertThat(card.chargeCalls).isEqualTo(1);
        assertThat(point.refundCalls).isZero();
        assertThat(card.refundCalls).isZero();
    }

    @Test
    @DisplayName("ConfirmedFailure가 발생하면 그 전에 성공한 component를 역순으로 refund한다")
    void onConfirmedFailureRefundsPriorSuccessesInReverseOrder() {
        StubMethod point = StubMethod.succeeding(PaymentMethodType.POINT, "ext-point");
        StubMethod card = StubMethod.failing(PaymentMethodType.CARD, FailureReason.CARD_DECLINED);
        PaymentComposer composer = new PaymentComposer(validator, List.of(point, card));

        CompositionResult result = composer.compose(List.of(
                req(PaymentMethodType.POINT, 50_000L),
                req(PaymentMethodType.CARD, 100_000L)
        ), 150_000L);

        assertThat(result).isInstanceOf(CompositionResult.ConfirmedFailure.class);
        CompositionResult.ConfirmedFailure failure = (CompositionResult.ConfirmedFailure) result;
        assertThat(failure.reason()).isEqualTo(FailureReason.CARD_DECLINED);
        assertThat(failure.failedAt()).isEqualTo(PaymentMethodType.CARD);
        assertThat(point.refundCalls).isEqualTo(1);
        assertThat(card.refundCalls).isZero();
    }

    @Test
    @DisplayName("첫 component부터 ConfirmedFailure이면 refund 대상이 없다")
    void confirmedFailureOnFirstComponentDoesNotRefundAnyone() {
        StubMethod point = StubMethod.failing(PaymentMethodType.POINT, FailureReason.INSUFFICIENT_POINT);
        StubMethod card = StubMethod.succeeding(PaymentMethodType.CARD, "ext-card");
        PaymentComposer composer = new PaymentComposer(validator, List.of(point, card));

        CompositionResult result = composer.compose(List.of(
                req(PaymentMethodType.POINT, 50_000L),
                req(PaymentMethodType.CARD, 100_000L)
        ), 150_000L);

        assertThat(result).isInstanceOf(CompositionResult.ConfirmedFailure.class);
        assertThat(point.refundCalls).isZero();
        assertThat(card.chargeCalls).isZero();
        assertThat(card.refundCalls).isZero();
    }

    @Test
    @DisplayName("ResultPending 발생 시 이미 성공한 component를 refund하지 않고 결과를 그대로 던진다 (Task 7로 위임)")
    void onResultPendingDoesNotRefundPriorSuccesses() {
        StubMethod point = StubMethod.succeeding(PaymentMethodType.POINT, "ext-point");
        StubMethod card = StubMethod.pending(PaymentMethodType.CARD, "ck-key");
        PaymentComposer composer = new PaymentComposer(validator, List.of(point, card));

        CompositionResult result = composer.compose(List.of(
                req(PaymentMethodType.POINT, 50_000L),
                req(PaymentMethodType.CARD, 100_000L)
        ), 150_000L);

        assertThat(result).isInstanceOf(CompositionResult.ResultPending.class);
        CompositionResult.ResultPending pending = (CompositionResult.ResultPending) result;
        assertThat(pending.pendingAt()).isEqualTo(PaymentMethodType.CARD);
        assertThat(pending.pgIdempotencyKey()).isEqualTo("ck-key");
        assertThat(pending.successfulComponents()).hasSize(1);
        assertThat(point.refundCalls).isZero();
    }

    @Test
    @DisplayName("CARD + Y_PAY 금지 조합은 charge 시작 전 Validator가 즉시 거절한다")
    void rejectsCardPlusYPayBeforeChargeStarts() {
        StubMethod card = StubMethod.succeeding(PaymentMethodType.CARD, "x");
        StubMethod ypay = StubMethod.succeeding(PaymentMethodType.Y_PAY, "y");
        PaymentComposer composer = new PaymentComposer(validator, List.of(card, ypay));

        assertThatThrownBy(() -> composer.compose(List.of(
                req(PaymentMethodType.CARD, 50_000L),
                req(PaymentMethodType.Y_PAY, 100_000L)
        ), 150_000L)).isInstanceOf(InvalidCompositionException.class);

        assertThat(card.chargeCalls).isZero();
        assertThat(ypay.chargeCalls).isZero();
    }

    @Test
    @DisplayName("같은 PaymentMethodType이 두 Bean으로 등록되면 생성 시점에 IllegalStateException을 던진다")
    void rejectsDuplicatePaymentMethodTypeAtConstruction() {
        StubMethod card1 = StubMethod.succeeding(PaymentMethodType.CARD, "x");
        StubMethod card2 = StubMethod.succeeding(PaymentMethodType.CARD, "y");

        assertThatThrownBy(() -> new PaymentComposer(validator, List.of(card1, card2)))
                .isInstanceOf(IllegalStateException.class);
    }

    private static ChargeRequest req(PaymentMethodType type, long amount) {
        return new ChargeRequest("ck-test", "user-1", type, amount);
    }

    static final class StubMethod implements PaymentMethod {
        private final PaymentMethodType type;
        private final ChargeOutcome outcome;
        final List<ChargeRequest> chargeArgs = new ArrayList<>();
        final List<ChargeRequest> refundArgs = new ArrayList<>();
        int chargeCalls;
        int refundCalls;

        private StubMethod(PaymentMethodType type, ChargeOutcome outcome) {
            this.type = type;
            this.outcome = outcome;
        }

        static StubMethod succeeding(PaymentMethodType type, String externalTxId) {
            return new StubMethod(type, new ChargeOutcome.Succeeded(type, externalTxId));
        }

        static StubMethod failing(PaymentMethodType type, FailureReason reason) {
            return new StubMethod(type, new ChargeOutcome.ConfirmedFailure(type, reason));
        }

        static StubMethod pending(PaymentMethodType type, String pgKey) {
            return new StubMethod(type, new ChargeOutcome.ResultPending(type, pgKey));
        }

        @Override
        public PaymentMethodType type() {
            return type;
        }

        @Override
        public ChargeOutcome charge(ChargeRequest request) {
            chargeCalls++;
            chargeArgs.add(request);
            return outcome;
        }

        @Override
        public void refund(ChargeRequest request, ChargeOutcome.Succeeded prior) {
            refundCalls++;
            refundArgs.add(request);
        }
    }
}
