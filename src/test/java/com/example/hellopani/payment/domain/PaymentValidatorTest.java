package com.example.hellopani.payment.domain;

import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentValidatorTest {

    private final PaymentValidator validator = new PaymentValidator();

    @Test
    void acceptsCardOnly() {
        validator.validate(List.of(
                new ChargeRequest("ck", "u", PaymentMethodType.CARD, 150_000L)
        ), 150_000L);
    }

    @Test
    void acceptsYPayOnly() {
        validator.validate(List.of(
                new ChargeRequest("ck", "u", PaymentMethodType.Y_PAY, 150_000L)
        ), 150_000L);
    }

    @Test
    void acceptsPointOnly() {
        validator.validate(List.of(
                new ChargeRequest("ck", "u", PaymentMethodType.POINT, 150_000L)
        ), 150_000L);
    }

    @Test
    void acceptsCardPlusPoint() {
        validator.validate(List.of(
                new ChargeRequest("ck", "u", PaymentMethodType.POINT, 50_000L),
                new ChargeRequest("ck", "u", PaymentMethodType.CARD, 100_000L)
        ), 150_000L);
    }

    @Test
    void acceptsYPayPlusPoint() {
        validator.validate(List.of(
                new ChargeRequest("ck", "u", PaymentMethodType.POINT, 50_000L),
                new ChargeRequest("ck", "u", PaymentMethodType.Y_PAY, 100_000L)
        ), 150_000L);
    }

    @Test
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
    void rejectsAmountSumMismatch() {
        List<ChargeRequest> requests = List.of(
                new ChargeRequest("ck", "u", PaymentMethodType.CARD, 100_000L)
        );

        assertThatThrownBy(() -> validator.validate(requests, 150_000L))
                .isInstanceOf(AmountMismatchException.class)
                .hasMessageContaining("100000");
    }

    @Test
    void rejectsEmptyRequests() {
        assertThatThrownBy(() -> validator.validate(List.of(), 150_000L))
                .isInstanceOf(InvalidCompositionException.class);
    }

    @Test
    void rejectsZeroOrNegativeAmount() {
        List<ChargeRequest> requests = List.of(
                new ChargeRequest("ck", "u", PaymentMethodType.CARD, 0L)
        );

        assertThatThrownBy(() -> validator.validate(requests, 0L))
                .isInstanceOf(InvalidCompositionException.class);
    }

    @Test
    void chargeOutcomeTypeIsAccessibleOnAllVariants() {
        ChargeOutcome a = new ChargeOutcome.Succeeded(PaymentMethodType.CARD, "ext-1");
        ChargeOutcome b = new ChargeOutcome.ConfirmedFailure(PaymentMethodType.CARD, FailureReason.CARD_DECLINED);
        ChargeOutcome c = new ChargeOutcome.ResultPending(PaymentMethodType.CARD, "key-1");

        assertThat(a.type()).isEqualTo(PaymentMethodType.CARD);
        assertThat(b.type()).isEqualTo(PaymentMethodType.CARD);
        assertThat(c.type()).isEqualTo(PaymentMethodType.CARD);
    }
}
