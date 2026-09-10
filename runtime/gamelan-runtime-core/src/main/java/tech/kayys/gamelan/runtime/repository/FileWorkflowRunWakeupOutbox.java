package tech.kayys.gamelan.runtime.repository;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.quarkus.arc.properties.IfBuildProperty;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import tech.kayys.gamelan.engine.event.WorkflowRunUpdateEvent;
import tech.kayys.gamelan.engine.event.WorkflowRunWakeupDeadLetter;
import tech.kayys.gamelan.engine.event.WorkflowRunWakeupDeadLetterReasons;
import tech.kayys.gamelan.engine.event.WorkflowRunWakeupIntent;
import tech.kayys.gamelan.engine.event.WorkflowRunWakeupOutbox;
import tech.kayys.gamelan.engine.event.WorkflowRunWakeupOutbox.DeadLetterPurgePolicy;
import tech.kayys.gamelan.engine.event.WorkflowRunWakeupOutbox.DeadLetterPurgeResult;
import tech.kayys.gamelan.engine.event.WorkflowRunWakeupOutbox.DeadLetterQuery;

@ApplicationScoped
@IfBuildProperty(name = "gamelan.workflow.persistence.store", stringValue = "file")
public class FileWorkflowRunWakeupOutbox implements WorkflowRunWakeupOutbox {

    private static final Logger LOG = LoggerFactory.getLogger(FileWorkflowRunWakeupOutbox.class);
    private static final int DEFAULT_MAX_PENDING_WAKEUPS = 10_000;
    private static final int DEFAULT_MAX_DELIVERY_ATTEMPTS = 100;
    private static final Duration DEFAULT_LEASE_DURATION = Duration.ofSeconds(30);
    private static final Duration DEFAULT_RETRY_BACKOFF = Duration.ofSeconds(5);

    private final Path rootDirectory;
    private final ObjectMapper fallbackObjectMapper;
    private final Set<Path> unreadableRecordWarnings = ConcurrentHashMap.newKeySet();
    private final String generatedOwnerId = UUID.randomUUID().toString();

    @Inject
    ObjectMapper objectMapper;

    @ConfigProperty(name = "gamelan.workflow.wakeup.max-pending", defaultValue = "10000")
    int maxPendingWakeups = DEFAULT_MAX_PENDING_WAKEUPS;

    @ConfigProperty(name = "gamelan.workflow.wakeup.max-delivery-attempts", defaultValue = "100")
    int maxDeliveryAttempts = DEFAULT_MAX_DELIVERY_ATTEMPTS;

    @ConfigProperty(name = "gamelan.workflow.wakeup.lease-duration", defaultValue = "30s")
    Duration leaseDuration = DEFAULT_LEASE_DURATION;

    @ConfigProperty(name = "gamelan.workflow.wakeup.retry-backoff", defaultValue = "5s")
    Duration retryBackoff = DEFAULT_RETRY_BACKOFF;

    @ConfigProperty(name = "gamelan.workflow.wakeup.outbox-owner-id", defaultValue = "")
    String configuredOwnerId = "";

    @Inject
    public FileWorkflowRunWakeupOutbox(
            @ConfigProperty(name = "gamelan.workflow.persistence.file.root", defaultValue = ".gamelan/workflow")
            String workflowRootDirectory,
            @ConfigProperty(name = "gamelan.workflow.wakeup.file.root")
            Optional<String> wakeupRootDirectory) {
        this(wakeupRootDirectory
                .map(FilePersistenceSupport::root)
                .orElseGet(() -> FilePersistenceSupport.root(workflowRootDirectory).resolve("workflow-wakeups")),
                FilePersistenceSupport.objectMapper());
    }

    public FileWorkflowRunWakeupOutbox(Path rootDirectory) {
        this(rootDirectory.toAbsolutePath().normalize(), FilePersistenceSupport.objectMapper());
    }

    FileWorkflowRunWakeupOutbox(Path rootDirectory, ObjectMapper objectMapper) {
        this.rootDirectory = Objects.requireNonNull(rootDirectory, "rootDirectory").toAbsolutePath().normalize();
        this.fallbackObjectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    @Override
    public Uni<WorkflowRunWakeupIntent> enqueue(WorkflowRunUpdateEvent event) {
        Objects.requireNonNull(event, "WorkflowRunUpdateEvent cannot be null");
        return Uni.createFrom().item(() -> {
            Path path = wakeupPath(event);
            return FilePersistenceSupport.withFileLock(rootDirectory, path, () -> {
                StoredWakeup current = readSafely(path).orElse(null);
                if (current == null && isAtCapacity()) {
                    throw new IllegalStateException("workflow run wake-up outbox is full");
                }
                Instant now = Instant.now();
                StoredWakeup next = current != null
                        ? current.replaceWith(event)
                        : new StoredWakeup(WorkflowRunWakeupIntent.pending(event, now), null, null);
                next = next.withLease(ownerId(), leaseExpiresAt(now));
                FilePersistenceSupport.writeAtomic(rootDirectory, path, next, mapper());
                unreadableRecordWarnings.remove(normalizePath(path));
                return next.intent();
            });
        });
    }

    @Override
    public Uni<List<WorkflowRunWakeupIntent>> pending(int maxItems) {
        return Uni.createFrom().item(() -> {
            int limit = maxItems > 0 ? maxItems : Integer.MAX_VALUE;
            return FilePersistenceSupport.listJsonFiles(rootDirectory).stream()
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .map(path -> FilePersistenceSupport.withFileLock(rootDirectory, path, () -> readSafely(path)))
                    .flatMap(Optional::stream)
                    .map(StoredWakeup::intent)
                    .filter(intent -> !intent.delivered())
                    .sorted(Comparator.comparing(WorkflowRunWakeupIntent::createdAt))
                    .limit(limit)
                    .toList();
        });
    }

    @Override
    public Uni<List<WorkflowRunWakeupIntent>> claimPending(int maxItems) {
        return Uni.createFrom().item(() -> {
            int limit = maxItems > 0 ? maxItems : Integer.MAX_VALUE;
            Instant now = Instant.now();
            return FilePersistenceSupport.listJsonFiles(rootDirectory).stream()
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .map(path -> FilePersistenceSupport.withFileLock(rootDirectory, path, () -> claim(path, now)))
                    .flatMap(Optional::stream)
                    .map(StoredWakeup::intent)
                    .filter(intent -> !intent.delivered())
                    .sorted(Comparator.comparing(WorkflowRunWakeupIntent::createdAt))
                    .limit(limit)
                    .toList();
        });
    }

    @Override
    public Uni<Void> markDelivered(String intentId, WorkflowRunUpdateEvent deliveredEvent) {
        String normalizedIntentId = normalizeIntentId(intentId);
        return Uni.createFrom().voidItem().invoke(() -> mutateByIntentId(normalizedIntentId, current -> {
            WorkflowRunWakeupIntent intent = current.intent();
            if (!current.isOwnedBy(ownerId())) {
                return WakeupMutationResult.keep(current);
            }
            if (deliveredEvent != null && !intent.event().equals(deliveredEvent)) {
                return WakeupMutationResult.keep(current);
            }
            return WakeupMutationResult.delete();
        }));
    }

    @Override
    public Uni<Void> markFailed(String intentId, Throwable error) {
        Objects.requireNonNull(error, "Wake-up delivery error cannot be null");
        String normalizedIntentId = normalizeIntentId(intentId);
        return Uni.createFrom().voidItem().invoke(() -> mutateByIntentId(normalizedIntentId, current -> {
            if (!current.isOwnedBy(ownerId())) {
                return WakeupMutationResult.keep(current);
            }
            Instant attemptedAt = Instant.now();
            StoredWakeup failed = current.markFailed(error, attemptedAt);
            if (failed.intent().attempts() >= effectiveMaxDeliveryAttempts()) {
                writeDeadLetter(
                        failed.intent(),
                        WorkflowRunWakeupDeadLetterReasons.MAX_DELIVERY_ATTEMPTS_EXCEEDED,
                        attemptedAt);
                return WakeupMutationResult.delete();
            }
            return WakeupMutationResult.keep(failed);
        }));
    }

    int pendingCount() {
        return FilePersistenceSupport.listJsonFiles(rootDirectory).size();
    }

    @Override
    public Uni<List<WorkflowRunWakeupDeadLetter>> deadLetters(int maxItems) {
        return deadLetters(new DeadLetterQuery(maxItems, null, null, null, null));
    }

    @Override
    public Uni<List<WorkflowRunWakeupDeadLetter>> deadLetters(DeadLetterQuery query) {
        DeadLetterQuery effectiveQuery = query != null ? query : DeadLetterQuery.all();
        return Uni.createFrom().item(() -> {
            return FilePersistenceSupport.listJsonFiles(deadLetterDirectory()).stream()
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .map(path -> FilePersistenceSupport.read(path, WorkflowRunWakeupDeadLetter.class, mapper()))
                    .filter(Objects::nonNull)
                    .filter(effectiveQuery::matches)
                    .sorted(Comparator.comparing(WorkflowRunWakeupDeadLetter::deadLetteredAt).reversed())
                    .limit(effectiveQuery.limit())
                    .toList();
        });
    }

    @Override
    public Uni<Long> deadLetterCount() {
        return Uni.createFrom().item(() -> (long) FilePersistenceSupport.listJsonFiles(deadLetterDirectory()).size());
    }

    @Override
    public Uni<Long> deadLetterCount(DeadLetterQuery query) {
        DeadLetterQuery effectiveQuery = query != null ? query : DeadLetterQuery.all();
        return Uni.createFrom().item(() -> FilePersistenceSupport.listJsonFiles(deadLetterDirectory()).stream()
                .map(path -> FilePersistenceSupport.read(path, WorkflowRunWakeupDeadLetter.class, mapper()))
                .filter(Objects::nonNull)
                .filter(effectiveQuery::matches)
                .count());
    }

    @Override
    public Uni<Optional<WorkflowRunWakeupIntent>> replayDeadLetter(String intentId) {
        String normalizedIntentId = normalizeIntentId(intentId);
        return Uni.createFrom().item(() -> {
            Path deadLetterPath = deadLetterPath(normalizedIntentId);
            return FilePersistenceSupport.withFileLock(rootDirectory, deadLetterPath, () -> {
                WorkflowRunWakeupDeadLetter deadLetter = FilePersistenceSupport.read(
                        deadLetterPath,
                        WorkflowRunWakeupDeadLetter.class,
                        mapper());
                if (deadLetter == null) {
                    return Optional.empty();
                }
                Path wakeupPath = wakeupPath(deadLetter.event());
                return FilePersistenceSupport.withFileLock(rootDirectory, wakeupPath, () -> {
                    if (readSafely(wakeupPath).isEmpty() && isAtCapacity()) {
                        throw new IllegalStateException("workflow run wake-up outbox is full");
                    }
                    WorkflowRunWakeupIntent replayed = WorkflowRunWakeupIntent.pending(deadLetter.event(), Instant.now());
                    StoredWakeup next = new StoredWakeup(replayed, null, null);
                    FilePersistenceSupport.writeAtomic(rootDirectory, wakeupPath, next, mapper());
                    FilePersistenceSupport.deleteIfExists(rootDirectory, deadLetterPath);
                    unreadableRecordWarnings.remove(normalizePath(wakeupPath));
                    return Optional.of(replayed);
                });
            });
        });
    }

    @Override
    public Uni<Boolean> deleteDeadLetter(String intentId) {
        String normalizedIntentId = normalizeIntentId(intentId);
        return Uni.createFrom().item(() -> deleteDeadLetterFile(normalizedIntentId));
    }

    @Override
    public Uni<DeadLetterPurgeResult> purgeDeadLetters(DeadLetterPurgePolicy policy) {
        DeadLetterPurgePolicy effectivePolicy = policy != null ? policy : DeadLetterPurgePolicy.disabled();
        if (!effectivePolicy.hasRetentionCriteria()) {
            return Uni.createFrom().item(DeadLetterPurgeResult.empty(effectivePolicy.dryRun()));
        }
        return Uni.createFrom().item(() -> {
            List<WorkflowRunWakeupDeadLetter> candidates = purgeCandidates(effectivePolicy, Instant.now());
            List<String> intentIds = candidates.stream()
                    .map(WorkflowRunWakeupDeadLetter::intentId)
                    .toList();
            int purged = 0;
            if (!effectivePolicy.dryRun()) {
                for (String intentId : intentIds) {
                    if (deleteDeadLetterFile(intentId)) {
                        purged++;
                    }
                }
            }
            return new DeadLetterPurgeResult(
                    candidates.size(),
                    effectivePolicy.dryRun() ? 0 : purged,
                    effectivePolicy.dryRun(),
                    intentIds);
        });
    }

    private boolean isAtCapacity() {
        return maxPendingWakeups > 0 && pendingCount() >= maxPendingWakeups;
    }

    private boolean deleteDeadLetterFile(String intentId) {
        Path path = deadLetterPath(intentId);
        return FilePersistenceSupport.withFileLock(rootDirectory, path, () -> {
            boolean exists = Files.isRegularFile(path);
            FilePersistenceSupport.deleteIfExists(rootDirectory, path);
            return exists;
        });
    }

    private List<WorkflowRunWakeupDeadLetter> purgeCandidates(DeadLetterPurgePolicy policy, Instant now) {
        List<WorkflowRunWakeupDeadLetter> matched = FilePersistenceSupport.listJsonFiles(deadLetterDirectory()).stream()
                .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                .map(path -> FilePersistenceSupport.read(path, WorkflowRunWakeupDeadLetter.class, mapper()))
                .filter(Objects::nonNull)
                .filter(policy.query()::matches)
                .sorted(Comparator.comparing(WorkflowRunWakeupDeadLetter::deadLetteredAt).reversed())
                .toList();
        return matched.stream()
                .skip(policy.retainLatest() >= 0 ? policy.retainLatest() : 0)
                .filter(deadLetter -> policy.matchesAge(deadLetter, now))
                .toList();
    }

    private Optional<StoredWakeup> claim(Path path, Instant now) {
        StoredWakeup current = readSafely(path).orElse(null);
        if (current == null
                || !current.isClaimableBy(ownerId(), now)
                || !current.isRetryReady(now, retryBackoffDuration())) {
            return Optional.empty();
        }
        StoredWakeup claimed = current.withLease(ownerId(), leaseExpiresAt(now));
        FilePersistenceSupport.writeAtomic(rootDirectory, path, claimed, mapper());
        unreadableRecordWarnings.remove(normalizePath(path));
        return Optional.of(claimed);
    }

    private void writeDeadLetter(WorkflowRunWakeupIntent intent, String reason, Instant deadLetteredAt) {
        WorkflowRunWakeupDeadLetter deadLetter = WorkflowRunWakeupDeadLetter.fromIntent(
                intent,
                reason,
                deadLetteredAt);
        FilePersistenceSupport.writeAtomic(rootDirectory, deadLetterPath(intent.id()), deadLetter, mapper());
    }

    private void mutateByIntentId(String intentId, WakeupMutation mutation) {
        for (Path path : FilePersistenceSupport.listJsonFiles(rootDirectory)) {
            boolean matched = FilePersistenceSupport.withFileLock(rootDirectory, path, () -> {
                StoredWakeup current = readSafely(path).orElse(null);
                if (current == null || !Objects.equals(current.intent().id(), intentId)) {
                    return false;
                }
                WakeupMutationResult result = mutation.apply(current);
                if (result.shouldDelete()) {
                    FilePersistenceSupport.deleteIfExists(rootDirectory, path);
                } else {
                    FilePersistenceSupport.writeAtomic(rootDirectory, path, result.wakeup(), mapper());
                }
                unreadableRecordWarnings.remove(normalizePath(path));
                return true;
            });
            if (matched) {
                return;
            }
        }
    }

    private Optional<StoredWakeup> readSafely(Path path) {
        try {
            StoredWakeup wakeup = FilePersistenceSupport.read(path, StoredWakeup.class, mapper());
            if (wakeup != null) {
                unreadableRecordWarnings.remove(normalizePath(path));
            }
            return Optional.ofNullable(wakeup);
        } catch (RuntimeException error) {
            warnUnreadableRecord(path, error);
            return Optional.empty();
        }
    }

    private void warnUnreadableRecord(Path path, RuntimeException error) {
        Path normalizedPath = normalizePath(path);
        if (unreadableRecordWarnings.add(normalizedPath)) {
            LOG.warn("Skipping unreadable workflow wake-up outbox record {}: {}", normalizedPath, errorSummary(error));
        } else {
            LOG.debug("Skipping unreadable workflow wake-up outbox record {}", normalizedPath, error);
        }
    }

    private Path wakeupPath(WorkflowRunUpdateEvent event) {
        return rootDirectory.resolve(FilePersistenceSupport.fileName(coalesceKey(event)));
    }

    private Path deadLetterPath(String intentId) {
        return deadLetterDirectory().resolve(FilePersistenceSupport.fileName(intentId));
    }

    private Path deadLetterDirectory() {
        return rootDirectory.resolve("dead-letters");
    }

    private ObjectMapper mapper() {
        return objectMapper != null ? objectMapper : fallbackObjectMapper;
    }

    private Instant leaseExpiresAt(Instant now) {
        return now.plus(positiveDuration(leaseDuration, DEFAULT_LEASE_DURATION));
    }

    private Duration retryBackoffDuration() {
        return nonNegativeDuration(retryBackoff, DEFAULT_RETRY_BACKOFF);
    }

    private int effectiveMaxDeliveryAttempts() {
        return maxDeliveryAttempts > 0 ? maxDeliveryAttempts : DEFAULT_MAX_DELIVERY_ATTEMPTS;
    }

    private String ownerId() {
        return configuredOwnerId != null && !configuredOwnerId.isBlank()
                ? configuredOwnerId.trim()
                : generatedOwnerId;
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

    private static Duration positiveDuration(Duration value, Duration fallback) {
        return value != null && !value.isZero() && !value.isNegative() ? value : fallback;
    }

    private static Duration nonNegativeDuration(Duration value, Duration fallback) {
        return value != null && !value.isNegative() ? value : fallback;
    }

    private static Path normalizePath(Path path) {
        return path.toAbsolutePath().normalize();
    }

    private static String errorSummary(RuntimeException error) {
        String message = error.getMessage();
        return message == null || message.isBlank()
                ? error.getClass().getSimpleName()
                : error.getClass().getSimpleName() + ": " + message.replaceAll("\\s+", " ").trim();
    }

    @FunctionalInterface
    private interface WakeupMutation {
        WakeupMutationResult apply(StoredWakeup current);
    }

    private record WakeupMutationResult(StoredWakeup wakeup, boolean shouldDelete) {
        static WakeupMutationResult keep(StoredWakeup wakeup) {
            return new WakeupMutationResult(Objects.requireNonNull(wakeup, "wakeup cannot be null"), false);
        }

        static WakeupMutationResult delete() {
            return new WakeupMutationResult(null, true);
        }
    }

    private record StoredWakeup(
            WorkflowRunWakeupIntent intent,
            String leaseOwner,
            Instant leaseExpiresAt) {
        StoredWakeup {
            Objects.requireNonNull(intent, "Wake-up intent cannot be null");
            leaseOwner = leaseOwner != null && !leaseOwner.isBlank() ? leaseOwner.trim() : null;
        }

        private StoredWakeup replaceWith(WorkflowRunUpdateEvent event) {
            return new StoredWakeup(intent.replaceWith(event), null, null);
        }

        private StoredWakeup markFailed(Throwable error, Instant attemptedAt) {
            return new StoredWakeup(intent.markFailed(error, attemptedAt), null, null);
        }

        private StoredWakeup withLease(String ownerId, Instant expiresAt) {
            return new StoredWakeup(intent, ownerId, expiresAt);
        }

        private boolean isOwnedBy(String ownerId) {
            return leaseOwner != null && leaseOwner.equals(ownerId);
        }

        private boolean isClaimableBy(String ownerId, Instant now) {
            return leaseOwner == null
                    || leaseExpiresAt == null
                    || !leaseExpiresAt.isAfter(now)
                    || leaseOwner.equals(ownerId);
        }

        private boolean isRetryReady(Instant now, Duration retryBackoff) {
            Instant lastAttemptAt = intent.lastAttemptAt();
            return lastAttemptAt == null || !lastAttemptAt.plus(retryBackoff).isAfter(now);
        }
    }
}
