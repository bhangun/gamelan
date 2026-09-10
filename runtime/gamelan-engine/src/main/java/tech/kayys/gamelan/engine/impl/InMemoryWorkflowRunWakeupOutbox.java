package tech.kayys.gamelan.engine.impl;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import io.quarkus.arc.DefaultBean;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import tech.kayys.gamelan.engine.event.WorkflowRunUpdateEvent;
import tech.kayys.gamelan.engine.event.WorkflowRunWakeupDeadLetter;
import tech.kayys.gamelan.engine.event.WorkflowRunWakeupDeadLetterReasons;
import tech.kayys.gamelan.engine.event.WorkflowRunWakeupIntent;
import tech.kayys.gamelan.engine.event.WorkflowRunWakeupOutbox;
import tech.kayys.gamelan.engine.event.WorkflowRunWakeupOutbox.DeadLetterPurgePolicy;
import tech.kayys.gamelan.engine.event.WorkflowRunWakeupOutbox.DeadLetterPurgeResult;
import tech.kayys.gamelan.engine.event.WorkflowRunWakeupOutbox.DeadLetterQuery;

/**
 * Local profile wake-up outbox. It coalesces pending wake-ups by tenant/run
 * because the event is level-triggered: one pending wake-up is enough to drive
 * the run to convergence.
 */
@ApplicationScoped
@DefaultBean
public class InMemoryWorkflowRunWakeupOutbox implements WorkflowRunWakeupOutbox {

    private static final int DEFAULT_MAX_PENDING_WAKEUPS = 10_000;
    private static final int DEFAULT_MAX_DELIVERY_ATTEMPTS = 100;

    @ConfigProperty(name = "gamelan.workflow.wakeup.max-pending", defaultValue = "10000")
    int maxPendingWakeups = DEFAULT_MAX_PENDING_WAKEUPS;

    @ConfigProperty(name = "gamelan.workflow.wakeup.max-delivery-attempts", defaultValue = "100")
    int maxDeliveryAttempts = DEFAULT_MAX_DELIVERY_ATTEMPTS;

    private final ConcurrentMap<String, StoredWakeup> pendingByKey = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, StoredWakeup> byId = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, WorkflowRunWakeupDeadLetter> deadLettersById = new ConcurrentHashMap<>();

    @Override
    public Uni<WorkflowRunWakeupIntent> enqueue(WorkflowRunUpdateEvent event) {
        Objects.requireNonNull(event, "WorkflowRunUpdateEvent cannot be null");
        String key = coalesceKey(event);
        StoredWakeup existing = pendingByKey.get(key);
        if (existing == null && maxPendingWakeups > 0 && pendingByKey.size() >= maxPendingWakeups) {
            return Uni.createFrom().failure(new IllegalStateException("workflow run wake-up outbox is full"));
        }

        StoredWakeup stored = pendingByKey.compute(key, (ignored, current) -> {
            if (current != null) {
                String previousIntentId = current.id();
                WorkflowRunWakeupIntent replacement = current.replaceEvent(event);
                byId.remove(previousIntentId, current);
                byId.put(replacement.id(), current);
                return current;
            }
            StoredWakeup created = new StoredWakeup(WorkflowRunWakeupIntent.pending(event, Instant.now()));
            byId.put(created.id(), created);
            return created;
        });
        return Uni.createFrom().item(stored.snapshot());
    }

    @Override
    public Uni<List<WorkflowRunWakeupIntent>> pending(int maxItems) {
        int limit = maxItems > 0 ? maxItems : Integer.MAX_VALUE;
        List<WorkflowRunWakeupIntent> intents = pendingByKey.values().stream()
                .map(StoredWakeup::snapshot)
                .filter(intent -> !intent.delivered())
                .sorted(Comparator.comparing(WorkflowRunWakeupIntent::createdAt))
                .limit(limit)
                .toList();
        return Uni.createFrom().item(intents);
    }

    @Override
    public Uni<Void> markDelivered(String intentId, WorkflowRunUpdateEvent deliveredEvent) {
        String normalizedIntentId = normalizeIntentId(intentId);
        StoredWakeup stored = byId.get(normalizedIntentId);
        if (stored != null) {
            WorkflowRunWakeupIntent delivered = stored.markDeliveredIfCurrent(
                    normalizedIntentId,
                    deliveredEvent,
                    Instant.now());
            if (delivered != null && byId.remove(normalizedIntentId, stored)) {
                pendingByKey.remove(coalesceKey(delivered.event()), stored);
            }
        }
        return Uni.createFrom().voidItem();
    }

    @Override
    public Uni<Void> markFailed(String intentId, Throwable error) {
        Objects.requireNonNull(error, "Wake-up delivery error cannot be null");
        String normalizedIntentId = normalizeIntentId(intentId);
        StoredWakeup stored = byId.get(normalizedIntentId);
        if (stored != null) {
            WorkflowRunWakeupIntent failed = stored.markFailedIfCurrent(normalizedIntentId, error, Instant.now());
            if (failed != null && failed.attempts() >= effectiveMaxDeliveryAttempts()) {
                deadLettersById.put(
                        failed.id(),
                        WorkflowRunWakeupDeadLetter.fromIntent(
                                failed,
                                WorkflowRunWakeupDeadLetterReasons.MAX_DELIVERY_ATTEMPTS_EXCEEDED,
                                Instant.now()));
                byId.remove(normalizedIntentId, stored);
                pendingByKey.remove(coalesceKey(failed.event()), stored);
            }
        }
        return Uni.createFrom().voidItem();
    }

    public int pendingCount() {
        return pendingByKey.size();
    }

    @Override
    public Uni<List<WorkflowRunWakeupDeadLetter>> deadLetters(int maxItems) {
        return deadLetters(new DeadLetterQuery(maxItems, null, null, null, null));
    }

    @Override
    public Uni<List<WorkflowRunWakeupDeadLetter>> deadLetters(DeadLetterQuery query) {
        DeadLetterQuery effectiveQuery = query != null ? query : DeadLetterQuery.all();
        List<WorkflowRunWakeupDeadLetter> deadLetters = deadLettersById.values().stream()
                .filter(effectiveQuery::matches)
                .sorted(Comparator.comparing(WorkflowRunWakeupDeadLetter::deadLetteredAt).reversed())
                .limit(effectiveQuery.limit())
                .toList();
        return Uni.createFrom().item(deadLetters);
    }

    @Override
    public Uni<Long> deadLetterCount() {
        return Uni.createFrom().item((long) deadLettersById.size());
    }

    @Override
    public Uni<Long> deadLetterCount(DeadLetterQuery query) {
        DeadLetterQuery effectiveQuery = query != null ? query : DeadLetterQuery.all();
        long count = deadLettersById.values().stream()
                .filter(effectiveQuery::matches)
                .count();
        return Uni.createFrom().item(count);
    }

    @Override
    public Uni<Optional<WorkflowRunWakeupIntent>> replayDeadLetter(String intentId) {
        String normalizedIntentId = normalizeIntentId(intentId);
        WorkflowRunWakeupDeadLetter deadLetter = deadLettersById.get(normalizedIntentId);
        if (deadLetter == null) {
            return Uni.createFrom().item(Optional.empty());
        }
        return enqueue(deadLetter.event())
                .invoke(ignored -> deadLettersById.remove(normalizedIntentId, deadLetter))
                .map(Optional::of);
    }

    @Override
    public Uni<Boolean> deleteDeadLetter(String intentId) {
        String normalizedIntentId = normalizeIntentId(intentId);
        return Uni.createFrom().item(deadLettersById.remove(normalizedIntentId) != null);
    }

    @Override
    public Uni<DeadLetterPurgeResult> purgeDeadLetters(DeadLetterPurgePolicy policy) {
        DeadLetterPurgePolicy effectivePolicy = policy != null ? policy : DeadLetterPurgePolicy.disabled();
        if (!effectivePolicy.hasRetentionCriteria()) {
            return Uni.createFrom().item(DeadLetterPurgeResult.empty(effectivePolicy.dryRun()));
        }
        List<WorkflowRunWakeupDeadLetter> candidates = purgeCandidates(effectivePolicy, Instant.now());
        if (!effectivePolicy.dryRun()) {
            candidates.forEach(deadLetter -> deadLettersById.remove(deadLetter.intentId(), deadLetter));
        }
        return Uni.createFrom().item(new DeadLetterPurgeResult(
                candidates.size(),
                effectivePolicy.dryRun() ? 0 : candidates.size(),
                effectivePolicy.dryRun(),
                candidates.stream().map(WorkflowRunWakeupDeadLetter::intentId).toList()));
    }

    private int effectiveMaxDeliveryAttempts() {
        return maxDeliveryAttempts > 0 ? maxDeliveryAttempts : DEFAULT_MAX_DELIVERY_ATTEMPTS;
    }

    private List<WorkflowRunWakeupDeadLetter> purgeCandidates(DeadLetterPurgePolicy policy, Instant now) {
        List<WorkflowRunWakeupDeadLetter> matched = deadLettersById.values().stream()
                .filter(policy.query()::matches)
                .sorted(Comparator.comparing(WorkflowRunWakeupDeadLetter::deadLetteredAt).reversed())
                .toList();
        return matched.stream()
                .skip(policy.retainLatest() >= 0 ? policy.retainLatest() : 0)
                .filter(deadLetter -> policy.matchesAge(deadLetter, now))
                .toList();
    }

    private static String normalizeIntentId(String intentId) {
        Objects.requireNonNull(intentId, "Wake-up intent id cannot be null");
        if (intentId.isBlank()) {
            throw new IllegalArgumentException("Wake-up intent id cannot be blank");
        }
        return intentId.trim();
    }

    private static String coalesceKey(WorkflowRunUpdateEvent event) {
        return (event.tenantId() != null ? event.tenantId() : "") + ":" + event.runId();
    }

    private static final class StoredWakeup {
        private WorkflowRunWakeupIntent intent;

        private StoredWakeup(WorkflowRunWakeupIntent intent) {
            this.intent = intent;
        }

        private synchronized String id() {
            return intent.id();
        }

        private synchronized WorkflowRunWakeupIntent snapshot() {
            return intent;
        }

        private synchronized WorkflowRunWakeupIntent replaceEvent(WorkflowRunUpdateEvent event) {
            intent = intent.replaceWith(event);
            return intent;
        }

        private synchronized WorkflowRunWakeupIntent markDeliveredIfCurrent(
                String intentId,
                WorkflowRunUpdateEvent deliveredEvent,
                Instant deliveredAt) {
            if (!intent.id().equals(intentId)) {
                return null;
            }
            if (deliveredEvent != null && !intent.event().equals(deliveredEvent)) {
                return null;
            }
            intent = intent.markDelivered(deliveredAt);
            return intent;
        }

        private synchronized WorkflowRunWakeupIntent markFailedIfCurrent(
                String intentId,
                Throwable error,
                Instant attemptedAt) {
            if (!intent.id().equals(intentId)) {
                return null;
            }
            intent = intent.markFailed(error, attemptedAt);
            return intent;
        }
    }
}
