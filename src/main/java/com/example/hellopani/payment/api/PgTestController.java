package com.example.hellopani.payment.api;

import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.example.hellopani.payment.application.PaymentResolutionJob;
import com.example.hellopani.payment.domain.FailureReason;
import com.example.hellopani.payment.domain.PgChargeResult;
import com.example.hellopani.payment.infra.FakePgClient;

/**
 * Dev/test 전용 — FakePgClient의 결과를 외부에서 강제하기 위한 진입점.
 *
 * <p>실 PG 미연결 환경에서 *결제 실패 / 결과 불명 → 보상* 흐름을 review.sh의 [p], [u] 메뉴로
 * 시연하기 위해 둔다. 실제 PG가 붙으면 자연히 의미가 없어지는 컨트롤러다.
 *
 * <p>FakePgClient 자체가 dev 전용이므로 별도 profile gating 없이 같은 톤을 유지한다.
 */
@RestController
@RequestMapping("/test/pg")
public class PgTestController {

    private final FakePgClient fakePgClient;
    private final PaymentResolutionJob paymentResolutionJob;

    public PgTestController(FakePgClient fakePgClient, PaymentResolutionJob paymentResolutionJob) {
        this.fakePgClient = fakePgClient;
        this.paymentResolutionJob = paymentResolutionJob;
    }

    /**
     * 다음 charge 호출의 결과를 미리 박는다 (pgIdempotencyKey 단위).
     * 우리 구현은 pgIdempotencyKey == checkoutId.
     */
    @PostMapping("/prime")
    public Map<String, String> prime(@RequestBody PrimeRequest request) {
        PgChargeResult result = switch (request.result().toUpperCase()) {
            case "APPROVED" -> new PgChargeResult.Approved("test-tx-" + System.currentTimeMillis());
            case "DECLINED" -> new PgChargeResult.Declined(FailureReason.CARD_DECLINED);
            case "LIMIT_EXCEEDED" -> new PgChargeResult.Declined(FailureReason.LIMIT_EXCEEDED);
            case "PENDING" -> new PgChargeResult.Pending(request.checkoutId());
            default -> throw new IllegalArgumentException(
                    "unknown result: " + request.result()
                            + " (APPROVED / DECLINED / LIMIT_EXCEEDED / PENDING 중 하나)");
        };
        fakePgClient.primeResult(request.checkoutId(), result);
        return Map.of("primed", request.result(), "checkoutId", request.checkoutId());
    }

    /**
     * PaymentResolutionJob을 즉시 동기 실행한다.
     * RESULT_PENDING 상태의 payment를 모두 다시 PG 조회 → 상태 전이.
     * 데모에서 30초(default interval) 안 기다리려고 둔다.
     */
    @PostMapping("/resolve-pending")
    public Map<String, String> resolvePending() {
        paymentResolutionJob.resolveAllPending();
        return Map.of("status", "triggered");
    }

    /**
     * FakePgClient의 prime 캐시를 비운다.
     */
    @PostMapping("/reset")
    public Map<String, String> reset() {
        fakePgClient.reset();
        return Map.of("status", "reset");
    }

    public record PrimeRequest(String checkoutId, String result) {
    }
}
