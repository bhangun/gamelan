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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.quarkus.arc.properties.IfBuildProperty;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.operators.multi.processors.UnicastProcessor;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import tech.kayys.gamelan.engine.node.NodeExecutionTask;
import tech.kayys.gamelan.scheduler.TaskQueue;
import tech.kayys.gamelan.scheduler.TaskQueueMetadata;

@ApplicationScoped
@IfBuildProperty(name = "gamelan.workflow.persistence.store", stringValue = "file")
public class FileTaskQueue implements TaskQueue {

    private static final Logger LOG = LoggerFactory.getLogger(FileTaskQueue.class);
    private static final Duration DEFAULT_LEASE_DURATION = Duration.ofSeconds(30);
    private static final Duration DEFAULT_LEASE_SCAN_INTERVAL = Duration.ofSeconds(1);
    private static final int DEFAULT_CLAIM_BATCH_SIZE = 100;

    private final Path rootDirectory;
    private final ObjectMapper objectMapper;
    private final Duration leaseDuration;
    private final Duration leaseScanInterval;
    private final int claimBatchSize;
    private final UnicastProcessor<QueuedTask> processor = UnicastProcessor.create();
    private final AtomicBoolean leaseScannerStarted = new AtomicBoolean();
    private final Set<Path> unreadableRecordWarnings = ConcurrentHashMap.newKeySet();
    private ScheduledExecutorService leaseScanner;

    @Inject
    public FileTaskQueue(
            @ConfigProperty(name = "gamelan.workflow.persistence.file.root", defaultValue = ".gamelan/workflow")
            String workflowRootDirectory,
            @ConfigProperty(name = "gamelan.task-queue.file.root")
            Optional<String> taskQueueRootDirectory,
            @ConfigProperty(name = "gamelan.task-queue.file.lease-duration", defaultValue = "30s")
            Duration leaseDuration,
            @ConfigProperty(name = "gamelan.task-queue.file.lease-scan-interval", defaultValue = "1s")
            Duration leaseScanInterval,
            @ConfigProperty(name = "gamelan.task-queue.file.claim-batch-size", defaultValue = "100")
            int claimBatchSize) {
        this(taskQueueRootDirectory
                .map(FilePersistenceSupport::root)
                .orElseGet(() -> FilePersistenceSupport.root(workflowRootDirectory).resolve("task-queue")),
                FilePersistenceSupport.objectMapper(),
                leaseDuration,
                leaseScanInterval,
                claimBatchSize);
    }

    public FileTaskQueue(Path rootDirectory) {
        this(rootDirectory, FilePersistenceSupport.objectMapper(), DEFAULT_LEASE_DURATION, DEFAULT_LEASE_SCAN_INTERVAL);
    }

    FileTaskQueue(
            Path rootDirectory,
            ObjectMapper objectMapper,
            Duration leaseDuration,
            Duration leaseScanInterval) {
        this(rootDirectory, objectMapper, leaseDuration, leaseScanInterval, DEFAULT_CLAIM_BATCH_SIZE);
    }

    FileTaskQueue(
            Path rootDirectory,
            ObjectMapper objectMapper,
            Duration leaseDuration,
            Duration leaseScanInterval,
            int claimBatchSize) {
        this.rootDirectory = Objects.requireNonNull(rootDirectory, "rootDirectory").toAbsolutePath().normalize();
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.leaseDuration = positiveDuration(leaseDuration, DEFAULT_LEASE_DURATION);
        this.leaseScanInterval = positiveDuration(leaseScanInterval, DEFAULT_LEASE_SCAN_INTERVAL);
        this.claimBatchSize = positiveInt(claimBatchSize, DEFAULT_CLAIM_BATCH_SIZE);
    }

    @Override
    public Uni<Void> enqueue(NodeExecutionTask task) {
        Objects.requireNonNull(task, "task cannot be null");
        return Uni.createFrom().voidItem().invoke(() -> {
            Instant now = Instant.now();
            NodeExecutionTask deliveryTask = TaskQueueMetadata.deliveryTask(task, now);
            String messageId = createMessageId();
            StoredQueuedTask stored = new StoredQueuedTask(
                    messageId,
                    deliveryTask,
                    null,
                    null,
                    now,
                    now);
            Path path = taskPath(messageId);
            boolean written = FilePersistenceSupport.writeAtomicIfAbsent(rootDirectory, path, stored, objectMapper);
            if (!written) {
                throw new IllegalStateException("Task queue message id already exists: " + messageId);
            }
            if (leaseScannerStarted.get()) {
                emitClaimable(now);
            }
        });
    }

    @Override
    public Multi<QueuedTask> consume() {
        startLeaseScanner();
        emitClaimable(Instant.now());
        return processor;
    }

    @Override
    public Uni<Void> acknowledge(String messageId) {
        String normalizedMessageId = normalizeMessageId(messageId);
        if (normalizedMessageId == null) {
            return Uni.createFrom().voidItem();
        }
        return Uni.createFrom().voidItem().invoke(() -> {
            Path path = taskPath(normalizedMessageId);
            FilePersistenceSupport.withFileLock(rootDirectory, path, () -> {
                FilePersistenceSupport.deleteIfExists(rootDirectory, path);
                return null;
            });
        });
    }

    @Override
    public Uni<Void> acknowledge(QueuedTask queuedTask) {
        Objects.requireNonNull(queuedTask, "queuedTask cannot be null");
        return Uni.createFrom().voidItem().invoke(() -> {
            Path path = taskPath(queuedTask.messageId());
            FilePersistenceSupport.withFileLock(rootDirectory, path, () -> {
                Optional<StoredQueuedTask> stored = readSafely(path);
                if (stored.isPresent() && Objects.equals(stored.get().leaseId(), queuedTask.leaseId())) {
                    FilePersistenceSupport.deleteIfExists(rootDirectory, path);
                }
                return null;
            });
        });
    }

    @Override
    public Uni<Boolean> renewLease(QueuedTask queuedTask, Duration leaseDuration) {
        Objects.requireNonNull(queuedTask, "queuedTask cannot be null");
        return Uni.createFrom().item(() -> {
            Instant now = Instant.now();
            Path path = taskPath(queuedTask.messageId());
            return FilePersistenceSupport.withFileLock(rootDirectory, path, () -> {
                StoredQueuedTask stored = readSafely(path).orElse(null);
                if (stored == null
                        || !Objects.equals(stored.leaseId(), queuedTask.leaseId())
                        || !isActive(stored, now)) {
                    return false;
                }
                StoredQueuedTask renewed = stored.withLease(
                        stored.leaseId(),
                        now.plus(positiveDuration(leaseDuration, this.leaseDuration)),
                        now);
                FilePersistenceSupport.writeAtomic(rootDirectory, path, renewed, objectMapper);
                return true;
            });
        });
    }

    @PreDestroy
    void stopLeaseScanner() {
        if (leaseScanner != null) {
            leaseScanner.shutdownNow();
        }
    }

    int emitClaimable(Instant now) {
        Instant reference = now != null ? now : Instant.now();
        int emitted = 0;
        List<Path> paths = FilePersistenceSupport.listJsonFiles(rootDirectory).stream()
                .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                .toList();
        for (Path path : paths) {
            if (emitted >= claimBatchSize) {
                break;
            }
            Optional<QueuedTask> claimed = claim(path, reference);
            if (claimed.isPresent()) {
                processor.onNext(claimed.get());
                emitted++;
            }
        }
        return emitted;
    }

    long storedTaskCount() {
        return FilePersistenceSupport.listJsonFiles(rootDirectory).size();
    }

    @Override
    public Uni<TaskQueue.QueueStats> stats() {
        return Uni.createFrom().item(() -> stats(Instant.now()));
    }

    TaskQueue.QueueStats stats(Instant now) {
        Instant reference = now != null ? now : Instant.now();
        long total = 0;
        long available = 0;
        long leased = 0;
        long expired = 0;
        long unreadable = 0;
        List<Path> paths = FilePersistenceSupport.listJsonFiles(rootDirectory);
        for (Path path : paths) {
            total++;
            QueueRecordState state = inspect(path, reference);
            switch (state) {
                case AVAILABLE -> available++;
                case LEASED -> leased++;
                case EXPIRED -> expired++;
                case UNREADABLE -> unreadable++;
                case MISSING -> total--;
            }
        }
        return TaskQueue.QueueStats.known(total, available, leased, expired, unreadable);
    }

    private QueueRecordState inspect(Path path, Instant reference) {
        return FilePersistenceSupport.withFileLock(rootDirectory, path, () -> {
            try {
                StoredQueuedTask stored = read(path);
                if (stored == null || !Files.isRegularFile(path)) {
                    return QueueRecordState.MISSING;
                }
                unreadableRecordWarnings.remove(normalizePath(path));
                if (stored.leaseId() == null) {
                    return QueueRecordState.AVAILABLE;
                }
                return isExpired(stored, reference)
                        ? QueueRecordState.EXPIRED
                        : QueueRecordState.LEASED;
            } catch (RuntimeException error) {
                warnUnreadableRecord(path, error);
                return QueueRecordState.UNREADABLE;
            }
        });
    }

    private Optional<QueuedTask> claim(Path path, Instant reference) {
        return FilePersistenceSupport.withFileLock(rootDirectory, path, () -> {
            StoredQueuedTask stored = readSafely(path).orElse(null);
            if (stored == null || !isClaimable(stored, reference) || !Files.isRegularFile(path)) {
                return Optional.empty();
            }
            boolean expired = isExpired(stored, reference);
            NodeExecutionTask task = expired
                    ? TaskQueueMetadata.redeliveredTask(stored.queuedTask())
                    : stored.task();
            StoredQueuedTask updated = new StoredQueuedTask(
                    stored.messageId(),
                    task,
                    createLeaseId(),
                    reference.plus(leaseDuration),
                    stored.createdAt(),
                    reference);
            FilePersistenceSupport.writeAtomic(rootDirectory, path, updated, objectMapper);
            return Optional.of(updated.queuedTask());
        });
    }

    private void startLeaseScanner() {
        if (!leaseScannerStarted.compareAndSet(false, true)) {
            return;
        }
        leaseScanner = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "gamelan-file-task-lease-scanner");
            thread.setDaemon(true);
            return thread;
        });
        long scanIntervalMillis = Math.max(1L, leaseScanInterval.toMillis());
        leaseScanner.scheduleAtFixedRate(
                this::scanClaimableTasks,
                scanIntervalMillis,
                scanIntervalMillis,
                TimeUnit.MILLISECONDS);
    }

    private void scanClaimableTasks() {
        try {
            emitClaimable(Instant.now());
        } catch (RuntimeException error) {
            LOG.warn("File task queue lease scan failed; will retry on the next interval: {}", errorSummary(error));
            LOG.debug("File task queue lease scan failure", error);
        }
    }

    private StoredQueuedTask read(Path path) {
        return FilePersistenceSupport.read(path, StoredQueuedTask.class, objectMapper);
    }

    private Optional<StoredQueuedTask> readSafely(Path path) {
        try {
            StoredQueuedTask stored = read(path);
            if (stored != null) {
                unreadableRecordWarnings.remove(normalizePath(path));
            }
            return Optional.ofNullable(stored);
        } catch (RuntimeException error) {
            warnUnreadableRecord(path, error);
            return Optional.empty();
        }
    }

    private void warnUnreadableRecord(Path path, RuntimeException error) {
        Path normalizedPath = normalizePath(path);
        if (unreadableRecordWarnings.add(normalizedPath)) {
            LOG.warn("Skipping unreadable file task queue record {}: {}", normalizedPath, errorSummary(error));
        } else {
            LOG.debug("Skipping unreadable file task queue record {}", normalizedPath, error);
        }
    }

    private Path taskPath(String messageId) {
        return rootDirectory.resolve(FilePersistenceSupport.fileName(messageId));
    }

    private static boolean isClaimable(StoredQueuedTask stored, Instant now) {
        return stored.leaseId() == null || isExpired(stored, now);
    }

    private static boolean isActive(StoredQueuedTask stored, Instant now) {
        return stored.leaseId() != null
                && stored.leaseExpiresAt() != null
                && stored.leaseExpiresAt().isAfter(now);
    }

    private static boolean isExpired(StoredQueuedTask stored, Instant now) {
        return stored.leaseId() != null
                && (stored.leaseExpiresAt() == null || !stored.leaseExpiresAt().isAfter(now));
    }

    private static Duration positiveDuration(Duration value, Duration fallback) {
        return value != null && !value.isZero() && !value.isNegative()
                ? value
                : fallback;
    }

    private static int positiveInt(int value, int fallback) {
        return value > 0 ? value : fallback;
    }

    private static String normalizeMessageId(String messageId) {
        return messageId != null && !messageId.isBlank() ? messageId.trim() : null;
    }

    private static String createMessageId() {
        return UUID.randomUUID().toString();
    }

    private static String createLeaseId() {
        return UUID.randomUUID().toString();
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

    private enum QueueRecordState {
        AVAILABLE,
        LEASED,
        EXPIRED,
        UNREADABLE,
        MISSING
    }

    record StoredQueuedTask(
            String messageId,
            NodeExecutionTask task,
            String leaseId,
            Instant leaseExpiresAt,
            Instant createdAt,
            Instant updatedAt) {

        StoredQueuedTask {
            Objects.requireNonNull(messageId, "messageId cannot be null");
            Objects.requireNonNull(task, "task cannot be null");
            createdAt = createdAt != null ? createdAt : Instant.now();
            updatedAt = updatedAt != null ? updatedAt : createdAt;
        }

        QueuedTask queuedTask() {
            return new QueuedTask(messageId, task, leaseId, leaseExpiresAt);
        }

        StoredQueuedTask withLease(String newLeaseId, Instant newLeaseExpiresAt, Instant now) {
            return new StoredQueuedTask(messageId, task, newLeaseId, newLeaseExpiresAt, createdAt, now);
        }
    }
}
