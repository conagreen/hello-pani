package com.example.hellopani.payment.domain;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("PaymentValidator — 결제 조합 / 합계 / 양수 금액 검증")
class PaymentValidatorTest {

    private final PaymentValidator validator = new PaymentValidator();

    @Test
    @DisplayName("CARD 단독 조합은 합계 일치 시 통과한다")
    void acceptsCardOnly() {
        validator.validate(List.of(
                new ChargeRequest("ck", "u", PaymentMethodType.CARD, 150_000L)
        ), 150_000L);
    }

    @Test
    @DisplayName("Y_PAY 단독 조합은 합계 일치 시 통과한다")
    void acceptsYPayOnly() {
        validator.validate(List.of(
                new ChargeRequest("ck", "u", PaymentMethodType.Y_PAY, 150_000L)
        ), 150_000L);
    }

    @Test
    @DisplayName("POINT 단독 조합은 합계 일치 시 통과한다")
    void acceptsPointOnly() {
        validator.validate(List.of(
                new ChargeRequest("ck", "u", PaymentMethodType.POINT, 150_000L)
        ), 150_000L);
    }

    @Test
    @DisplayName("CARD + POINT 복합 조합은 허용된다")
    void acceptsCardPlusPoint() {
        validator.validate(List.of(
                new ChargeRequest("ck", "u", PaymentMethodType.POINT, 50_000L),
                new ChargeRequest("ck", "u", PaymentMethodType.CARD, 100_000L)
        ), 150_000L);
    }

    @Test
    @DisplayName("Y_PAY + POINT 복합 조합은 허용된다")
    void acceptsYPayPlusPoint() {
        validator.validate(List.of(
                new ChargeRequest("ck", "u", PaymentMethodType.POINT, 50_000L),
                new ChargeRequest("ck", "u", PaymentMethodType.Y_PAY, 100_000L)
        ), 150_000L);
    }

    @Test
    @DisplayName("CARD + Y_PAY 조합은 외부 결제망 충돌로 InvalidCompositionException을 던진다")
    void rejectsCardPlusYPay() {
        List<ChargeRequest> requests = List.of(
                new ChargeRequest("ck", "u", PaymentMethodType.CARD, 50_000L),
                new ChargeRequest("ck", "u", PaymentMethodType.Y_PAY, 100_000L)
        );

        assertThatThrownBy(() -> validator.validate(requests, 150_000L))
                .isInstanceOf(InvalidCompositionException.class)
                .hasMessageContaining("CARD + Y_PAY");
    }

    @Test
    @DisplayName("CARD + Y_PAY + POINT 3종 조합도 거절된다")
    void rejectsCardPlusYPayPlusPoint() {
        List<ChargeRequest> requests = List.of(
                new ChargeRequest("ck", "u", PaymentMethodType.POINT, 30_000L),
                new ChargeRequest("ck", "u", PaymentMethodType.CARD, 50_000L),
                new ChargeRequest("ck", "u", PaymentMethodType.Y_PAY, 70_000L)
        );

        assertThatThrownBy(() -> validator.validate(requests, 150_000L))
                .isInstanceOf(InvalidCompositionException.class);
    }

    @Test
    @DisplayName("component 합계가 expectedTotal과 다르면 AmountMismatchException을 던진다")
    void rejectsAmountSumMismatch() {
        List<ChargeRequest> requests = List.of(
                new ChargeRequest("ck", "u", PaymentMethodType.CARD, 100_000L)
        );

        assertThatThrownBy(() -> validator.validate(requests, 150_000L))
                .isInstanceOf(AmountMismatchException.class)
                .hasMessageContaining("100000");
    }

    @Test
    @DisplayName("빈 component 리스트는 거절된다")
    void rejectsEmptyRequests() {
        assertThatThrownBy(() -> validator.validate(List.of(), 150_000L))
                .isInstanceOf(InvalidCompositionException.class);
    }

    @Test
    @DisplayName("0 또는 음수 금액의 component는 거절된다")
    void rejectsZeroOrNegativeAmount() {
        List<ChargeRequest> requests = List.of(
                new ChargeRequest("ck", "u", PaymentMethodType.CARD, 0L)
        );

        assertThatThrownBy(() -> validator.validate(requests, 0L))
                .isInstanceOf(InvalidCompositionException.class);
    }

    @Test
    @DisplayName("ChargeOutcome sealed의 모든 variant에서 type()이 노출된다")
    void chargeOutcomeTypeIsAccessibleOnAllVariants() {
        ChargeOutcome a = new ChargeOutcome.Succeeded(PaymentMethodType.CARD, "ext-1");
        ChargeOutcome b = new ChargeOutcome.ConfirmedFailure(PaymentMethodType.CARD, FailureReason.CARD_DECLINED);
        ChargeOutcome c = new ChargeOutcome.ResultPending(PaymentMethodType.CARD, "key-1");

        assertThat(a.type()).isEqualTo(PaymentMethodType.CARD);
        assertThat(b.type()).isEqualTo(PaymentMethodType.CARD);
        assertThat(c.type()).isEqualTo(PaymentMethodType.CARD);
    }
}
