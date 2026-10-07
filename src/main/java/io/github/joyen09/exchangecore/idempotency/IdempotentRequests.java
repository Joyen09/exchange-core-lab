package io.github.joyen09.exchangecore.idempotency;

import io.github.joyen09.exchangecore.idempotency.IdempotencyExceptions.KeyReusedException;
import io.github.joyen09.exchangecore.idempotency.IdempotencyExceptions.RequestInProgressException;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs a request at most once per {@code Idempotency-Key}, and replays the recorded response for
 * repeats.
 *
 * <h2>Why the claim commits before the work</h2>
 *
 * The claim is inserted and committed in its own transaction, <em>before</em> the business work runs.
 * That ordering is what makes a concurrent duplicate observable: if the claim were part of the work's
 * transaction, a second request's {@code ON CONFLICT DO NOTHING} would block on the uncommitted row
 * until the first finished, and the client would wait instead of being told to retry. SPEC §3.3 asks
 * for 409, and 409 is only possible if somebody can see the claim.
 *
 * <p>The cost is a compensating delete: if the work fails, the claim must be released or the key would
 * answer 409 for its whole retention period. And if the process dies between the two commits, nothing
 * runs the compensation — which is what {@link IdempotencyRepository#deleteAbandoned} is for.
 *
 * <p>No application-level lock is used anywhere here. A {@code synchronized} block or a local lock
 * would be wrong rather than merely insufficient: it stops working the moment a second instance exists,
 * and the correctness of this is supposed to rest on the database.
 */
@Service
public class IdempotentRequests {

    /** What to record and replay: the status, the body, and the resource the request created. */
    public record Outcome(int httpStatus, String responseBody, UUID resourceId) {}

    private final IdempotencyRepository repository;
    private final CanonicalRequest canonical;
    private final IdempotencyProperties properties;
    private final Clock clock;
    private final TransactionTemplate claimTransactions;
    private final TransactionTemplate workTransactions;

    public IdempotentRequests(
            IdempotencyRepository repository,
            CanonicalRequest canonical,
            IdempotencyProperties properties,
            Clock clock,
            PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.canonical = canonical;
        this.properties = properties;
        this.clock = clock;

        this.claimTransactions = new TransactionTemplate(transactionManager);
        this.claimTransactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.workTransactions = new TransactionTemplate(transactionManager);
    }

    public Outcome execute(String key, String requestBody, Supplier<Outcome> work) {
        String fingerprint = canonical.fingerprint(requestBody);
        Instant now = clock.instant();

        boolean claimed = Boolean.TRUE.equals(claimTransactions.execute(
                status -> repository.claim(key, fingerprint, now, now.plus(properties.getRetention()))));

        if (!claimed) {
            return replay(key, fingerprint);
        }

        try {
            return workTransactions.execute(status -> {
                Outcome outcome = work.get();
                repository.complete(
                        key, outcome.httpStatus(), outcome.responseBody(), outcome.resourceId(), clock.instant());
                return outcome;
            });
        } catch (RuntimeException failure) {
            // The work did not happen, so the key must not stay claimed. Released in its own
            // transaction because the work's transaction is already rolling back.
            claimTransactions.executeWithoutResult(status -> repository.release(key));
            throw failure;
        }
    }

    private Outcome replay(String key, String fingerprint) {
        IdempotencyRecord existing = repository
                .find(key)
                .orElseThrow(() -> new IllegalStateException(
                        "key %s conflicted but no record was found — it may have just expired".formatted(key)));

        if (!existing.requestHash().equals(fingerprint)) {
            throw new KeyReusedException(key);
        }
        if (!existing.isComplete()) {
            throw new RequestInProgressException(key);
        }
        return new Outcome(existing.httpStatus(), existing.responseBody(), existing.resourceId());
    }

    /** Retention sweep, called from the scheduler. */
    public int purge() {
        Instant now = clock.instant();
        Integer removed = claimTransactions.execute(status ->
                repository.deleteExpired(now) + repository.deleteAbandoned(now.minus(properties.getAbandonedAfter())));
        return removed == null ? 0 : removed;
    }
}
