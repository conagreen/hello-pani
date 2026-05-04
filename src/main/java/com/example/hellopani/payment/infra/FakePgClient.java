package com.example.hellopani.payment.infra;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;
import com.example.hellopani.payment.domain.FailureReason;
import com.example.hellopani.payment.domain.PgChargeRequest;
import com.example.hellopani.payment.domain.PgChargeResult;
import com.example.hellopani.payment.domain.PgClient;

@Component
public class FakePgClient implements PgClient {

    public static final long TRIGGER_LIMIT_EXCEEDED = 999_999L;
    public static final long TRIGGER_CARD_DECLINED = 999_998L;
    public static final long TRIGGER_RESULT_PENDING = 999_997L;

    private final ConcurrentMap<String, PgChargeResult> resultsByKey = new ConcurrentHashMap<>();
    private final AtomicLong txCounter = new AtomicLong();

    @Override
    public PgChargeResult charge(PgChargeRequest request) {
        PgChargeResult result = resolve(request);
        resultsByKey.put(request.pgIdempotencyKey(), result);
        return result;
    }

    private PgChargeResult resolve(PgChargeRequest request) {
        long amount = request.amount();
        if (amount == TRIGGER_LIMIT_EXCEEDED) {
            return new PgChargeResult.Declined(FailureReason.LIMIT_EXCEEDED);
        }
        if (amount == TRIGGER_CARD_DECLINED) {
            return new PgChargeResult.Declined(FailureReason.CARD_DECLINED);
        }
        if (amount == TRIGGER_RESULT_PENDING) {
            return new PgChargeResult.Pending(request.pgIdempotencyKey());
        }
        return new PgChargeResult.Approved("fake-pg-" + txCounter.incrementAndGet());
    }

    @Override
    public PgChargeResult lookupResult(String pgIdempotencyKey) {
        PgChargeResult cached = resultsByKey.get(pgIdempotencyKey);
        return cached != null ? cached : new PgChargeResult.NotFound();
    }

    @Override
    public void refund(String pgIdempotencyKey) {
        resultsByKey.remove(pgIdempotencyKey);
    }

    public void reset() {
        resultsByKey.clear();
        txCounter.set(0);
    }

    /**
     * Test helper: pre-seed a PG result for a given idempotency key without going through {@link #charge}.
     * Useful when simulating "PG eventually returns Approved/Declined" for resolution-job tests.
     */
    public void primeResult(String pgIdempotencyKey, PgChargeResult result) {
        resultsByKey.put(pgIdempotencyKey, result);
    }
}
