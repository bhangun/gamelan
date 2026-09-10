package tech.kayys.gamelan.runtime.repository;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.quarkus.arc.properties.IfBuildProperty;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import tech.kayys.gamelan.engine.error.ErrorInfo;
import tech.kayys.gamelan.engine.event.CompensationCompletedEvent;
import tech.kayys.gamelan.engine.event.CompensationFailedEvent;
import tech.kayys.gamelan.engine.event.CompensationStartedEvent;
import tech.kayys.gamelan.engine.event.ExecutionEvent;
import tech.kayys.gamelan.engine.event.GenericExecutionEvent;
import tech.kayys.gamelan.engine.event.NodeCompletedEvent;
import tech.kayys.gamelan.engine.event.NodeFailedEvent;
import tech.kayys.gamelan.engine.event.NodeScheduledEvent;
import tech.kayys.gamelan.engine.event.NodeStartedEvent;
import tech.kayys.gamelan.engine.event.WorkflowCancelledEvent;
import tech.kayys.gamelan.engine.event.WorkflowCompletedEvent;
import tech.kayys.gamelan.engine.event.WorkflowFailedEvent;
import tech.kayys.gamelan.engine.event.WorkflowResumedEvent;
import tech.kayys.gamelan.engine.event.WorkflowStartedEvent;
import tech.kayys.gamelan.engine.event.WorkflowSuspendedEvent;
import tech.kayys.gamelan.engine.execution.ExecutionEventEnvelopes;
import tech.kayys.gamelan.engine.execution.ExecutionHistory;
import tech.kayys.gamelan.engine.execution.ExecutionHistoryRepository;
import tech.kayys.gamelan.engine.node.NodeId;
import tech.kayys.gamelan.engine.tenant.TenantId;
import tech.kayys.gamelan.engine.workflow.WorkflowRunId;

@ApplicationScoped
@IfBuildProperty(name = "gamelan.workflow.persistence.store", stringValue = "file")
public class FileExecutionHistoryRepository implements ExecutionHistoryRepository {

    private static final long DEFAULT_HISTORY_LOG_COMPACTION_BYTES = 1024 * 1024;
    private static final String COMPACTING_SUFFIX = ".compacting";

    private final Path rootDirectory;
    private final ObjectMapper fallbackObjectMapper;
    private final long historyLogCompactionBytes;

    @Inject
    ObjectMapper objectMapper;

    @Inject
    public FileExecutionHistoryRepository(
            @ConfigProperty(name = "gamelan.workflow.persistence.file.root", defaultValue = ".gamelan/workflow")
            String rootDirectory,
            @ConfigProperty(name = "gamelan.workflow.persistence.file.history-log-compaction-bytes", defaultValue = "1048576")
            long historyLogCompactionBytes) {
        this(FilePersistenceSupport.root(rootDirectory), FilePersistenceSupport.objectMapper(), historyLogCompactionBytes);
    }

    public FileExecutionHistoryRepository(Path rootDirectory) {
        this(rootDirectory.toAbsolutePath().normalize(), FilePersistenceSupport.objectMapper());
    }

    FileExecutionHistoryRepository(Path rootDirectory, ObjectMapper objectMapper) {
        this(rootDirectory, objectMapper, DEFAULT_HISTORY_LOG_COMPACTION_BYTES);
    }

    FileExecutionHistoryRepository(Path rootDirectory, ObjectMapper objectMapper, long historyLogCompactionBytes) {
        this.rootDirectory = Objects.requireNonNull(rootDirectory, "rootDirectory").toAbsolutePath().normalize();
        this.fallbackObjectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.historyLogCompactionBytes = historyLogCompactionBytes;
    }

    @Override
    public Uni<Void> append(WorkflowRunId runId, String type, String message, Map<String, Object> metadata) {
        return appendStored(runId, new StoredHistoryEvent(
                java.util.UUID.randomUUID().toString(),
                type,
                message,
                Instant.now(),
                metadata != null ? metadata : Map.of()));
    }

    @Override
    public Uni<Void> append(
            WorkflowRunId runId,
            TenantId tenantId,
            String type,
            String message,
            Map<String, Object> metadata) {
        return appendStored(runId, tenantId, new StoredHistoryEvent(
                java.util.UUID.randomUUID().toString(),
                type,
                message,
                Instant.now(),
                metadata != null ? metadata : Map.of()));
    }

    @Override
    public Uni<Void> appendEvents(WorkflowRunId runId, List<ExecutionEvent> events) {
        List<ExecutionEvent> safeEvents = ExecutionEventEnvelopes.validateForRun(runId, events);
        if (safeEvents.isEmpty()) {
            return Uni.createFrom().voidItem();
        }
        return Uni.createFrom().voidItem().invoke(() -> {
            appendHistory(runId, toStoredEvents(safeEvents));
        });
    }

    @Override
    public Uni<Void> appendEvents(WorkflowRunId runId, TenantId tenantId, List<ExecutionEvent> events) {
        List<ExecutionEvent> safeEvents = ExecutionEventEnvelopes.validateForRun(runId, tenantId, events);
        if (safeEvents.isEmpty()) {
            return Uni.createFrom().voidItem();
        }
        return Uni.createFrom().voidItem().invoke(() -> {
            appendHistory(runId, tenantId, toStoredEvents(safeEvents));
        });
    }

    @Override
    public Uni<ExecutionHistory> load(WorkflowRunId runId) {
        return Uni.createFrom().item(() -> {
            List<ExecutionEvent> events = readHistory(runId).events().stream()
                    .map(event -> new GenericExecutionEvent(
                            event.eventId(),
                            runId,
                            event.type(),
                            event.message(),
                            event.occurredAt(),
                            event.metadata()))
                    .map(ExecutionEvent.class::cast)
                    .toList();
            return ExecutionHistory.fromEvents(runId, events);
        });
    }

    @Override
    public Uni<ExecutionHistory> load(WorkflowRunId runId, TenantId tenantId) {
        return Uni.createFrom().item(() -> {
            List<ExecutionEvent> events = readHistory(runId, tenantId).events().stream()
                    .map(event -> new GenericExecutionEvent(
                            event.eventId(),
                            runId,
                            event.type(),
                            event.message(),
                            event.occurredAt(),
                            event.metadata()))
                    .map(ExecutionEvent.class::cast)
                    .toList();
            return ExecutionHistory.fromEvents(runId, events);
        });
    }

    @Override
    public Uni<Boolean> isNodeResultProcessed(WorkflowRunId runId, NodeId nodeId, int attempt) {
        return Uni.createFrom().item(() -> Files.isRegularFile(processedNodePath(runId, nodeId, attempt)));
    }

    @Override
    public Uni<Boolean> isNodeResultProcessed(WorkflowRunId runId, TenantId tenantId, NodeId nodeId, int attempt) {
        if (tenantId == null) {
            return isNodeResultProcessed(runId, nodeId, attempt);
        }
        return Uni.createFrom().item(() -> Files.isRegularFile(processedNodePath(runId, tenantId, nodeId, attempt))
                || Files.isRegularFile(processedNodePath(runId, nodeId, attempt)));
    }

    @Override
    public Uni<Boolean> markNodeResultProcessed(WorkflowRunId runId, NodeId nodeId, int attempt) {
        return Uni.createFrom().item(() -> {
            boolean claimed = FilePersistenceSupport.writeAtomicIfAbsent(
                    rootDirectory,
                    processedNodePath(runId, nodeId, attempt),
                    new ProcessedNodeResult(runId, null, nodeId, attempt, Instant.now()),
                    mapper());
            return claimed;
        });
    }

    @Override
    public Uni<Boolean> markNodeResultProcessed(WorkflowRunId runId, TenantId tenantId, NodeId nodeId, int attempt) {
        if (tenantId == null) {
            return markNodeResultProcessed(runId, nodeId, attempt);
        }
        return Uni.createFrom().item(() -> {
            if (Files.isRegularFile(processedNodePath(runId, nodeId, attempt))) {
                return false;
            }
            return FilePersistenceSupport.writeAtomicIfAbsent(
                    rootDirectory,
                    processedNodePath(runId, tenantId, nodeId, attempt),
                    new ProcessedNodeResult(runId, tenantId, nodeId, attempt, Instant.now()),
                    mapper());
        });
    }

    @Override
    public Uni<Boolean> isExternalSignalProcessed(WorkflowRunId runId, String idempotencyKey) {
        if (isBlank(idempotencyKey)) {
            return Uni.createFrom().item(false);
        }
        return Uni.createFrom().item(() -> Files.isRegularFile(processedExternalSignalPath(runId, idempotencyKey)));
    }

    @Override
    public Uni<Boolean> isExternalSignalProcessed(WorkflowRunId runId, TenantId tenantId, String idempotencyKey) {
        if (tenantId == null) {
            return isExternalSignalProcessed(runId, idempotencyKey);
        }
        if (isBlank(idempotencyKey)) {
            return Uni.createFrom().item(false);
        }
        return Uni.createFrom().item(() -> Files.isRegularFile(
                processedExternalSignalPath(runId, tenantId, idempotencyKey))
                || Files.isRegularFile(processedExternalSignalPath(runId, idempotencyKey)));
    }

    @Override
    public Uni<Boolean> markExternalSignalProcessed(WorkflowRunId runId, String idempotencyKey) {
        if (isBlank(idempotencyKey)) {
            return Uni.createFrom().item(false);
        }
        return Uni.createFrom().item(() -> FilePersistenceSupport.writeAtomicIfAbsent(
                rootDirectory,
                processedExternalSignalPath(runId, idempotencyKey),
                new ProcessedExternalSignal(runId, null, idempotencyKey, Instant.now()),
                mapper()));
    }

    @Override
    public Uni<Boolean> markExternalSignalProcessed(WorkflowRunId runId, TenantId tenantId, String idempotencyKey) {
        if (tenantId == null) {
            return markExternalSignalProcessed(runId, idempotencyKey);
        }
        if (isBlank(idempotencyKey)) {
            return Uni.createFrom().item(false);
        }
        return Uni.createFrom().item(() -> {
            if (Files.isRegularFile(processedExternalSignalPath(runId, idempotencyKey))) {
                return false;
            }
            return FilePersistenceSupport.writeAtomicIfAbsent(
                    rootDirectory,
                    processedExternalSignalPath(runId, tenantId, idempotencyKey),
                    new ProcessedExternalSignal(runId, tenantId, idempotencyKey, Instant.now()),
                    mapper());
        });
    }

    @Override
    public Uni<Boolean> isCompensationNodeProcessed(WorkflowRunId runId, NodeId nodeId) {
        return Uni.createFrom().item(() -> Files.isRegularFile(processedCompensationNodePath(runId, nodeId)));
    }

    @Override
    public Uni<Boolean> isCompensationNodeProcessed(WorkflowRunId runId, TenantId tenantId, NodeId nodeId) {
        if (tenantId == null) {
            return isCompensationNodeProcessed(runId, nodeId);
        }
        return Uni.createFrom().item(() -> Files.isRegularFile(
                processedCompensationNodePath(runId, tenantId, nodeId))
                || Files.isRegularFile(processedCompensationNodePath(runId, nodeId)));
    }

    @Override
    public Uni<Boolean> markCompensationNodeProcessed(WorkflowRunId runId, NodeId nodeId) {
        return Uni.createFrom().item(() -> FilePersistenceSupport.writeAtomicIfAbsent(
                rootDirectory,
                processedCompensationNodePath(runId, nodeId),
                new ProcessedCompensationNode(runId, null, nodeId, Instant.now()),
                mapper()));
    }

    @Override
    public Uni<Boolean> markCompensationNodeProcessed(WorkflowRunId runId, TenantId tenantId, NodeId nodeId) {
        if (tenantId == null) {
            return markCompensationNodeProcessed(runId, nodeId);
        }
        return Uni.createFrom().item(() -> {
            if (Files.isRegularFile(processedCompensationNodePath(runId, nodeId))) {
                return false;
            }
            return FilePersistenceSupport.writeAtomicIfAbsent(
                    rootDirectory,
                    processedCompensationNodePath(runId, tenantId, nodeId),
                    new ProcessedCompensationNode(runId, tenantId, nodeId, Instant.now()),
                    mapper());
        });
    }

    private Uni<Void> appendStored(WorkflowRunId runId, StoredHistoryEvent event) {
        return Uni.createFrom().voidItem().invoke(() -> {
            appendHistory(runId, List.of(event));
        });
    }

    private Uni<Void> appendStored(WorkflowRunId runId, TenantId tenantId, StoredHistoryEvent event) {
        return Uni.createFrom().voidItem().invoke(() -> {
            appendHistory(runId, tenantId, List.of(event));
        });
    }

    private StoredHistory readHistory(WorkflowRunId runId) {
        return readHistory(historyPath(runId));
    }

    private StoredHistory readHistory(WorkflowRunId runId, TenantId tenantId) {
        Path tenantPath = historyPath(runId, tenantId);
        StoredHistory stored = readHistory(tenantPath);
        if (stored.hasPersistentSource()) {
            return stored;
        }
        return readHistory(runId);
    }

    private StoredHistory readHistory(Path historyPath) {
        StoredHistorySnapshot stored = FilePersistenceSupport.read(historyPath, StoredHistorySnapshot.class, mapper());
        Set<String> seenEventIds = new HashSet<>();
        List<StoredHistoryEvent> events = new ArrayList<>();
        Instant updatedAt = Instant.now();
        if (stored != null) {
            addEvents(events, seenEventIds, stored.events());
            updatedAt = stored.updatedAt();
        }
        Path logPath = historyLogPath(historyPath);
        Path compactingPath = historyLogCompactingPath(logPath);
        addEvents(events, seenEventIds, readHistoryLog(compactingPath));
        addEvents(events, seenEventIds, readHistoryLog(logPath));
        if (!events.isEmpty()) {
            updatedAt = events.getLast().occurredAt();
        }
        return new StoredHistory(
                events,
                updatedAt,
                stored != null || Files.isRegularFile(logPath) || Files.isRegularFile(compactingPath));
    }

    private void addEvents(
            List<StoredHistoryEvent> target,
            Set<String> seenEventIds,
            List<StoredHistoryEvent> source) {
        for (StoredHistoryEvent event : source) {
            if (event.eventId() == null || seenEventIds.add(event.eventId())) {
                target.add(event);
            }
        }
    }

    private List<StoredHistoryEvent> readHistoryLog(Path historyLogPath) {
        if (!Files.isRegularFile(historyLogPath)) {
            return List.of();
        }
        try (var reader = Files.newBufferedReader(historyLogPath, StandardCharsets.UTF_8)) {
            List<StoredHistoryEvent> events = new ArrayList<>();
            String pending = null;
            String line;
            while ((line = reader.readLine()) != null) {
                appendRequiredHistoryLogLine(events, pending);
                pending = line;
            }
            if (pending != null && !pending.isBlank()) {
                try {
                    appendHistoryLogLine(events, pending);
                } catch (IOException trailingPartialLine) {
                    // A crash can leave the final append without its newline; keep prior durable entries readable.
                }
            }
            return events;
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    private void appendRequiredHistoryLogLine(List<StoredHistoryEvent> events, String line) throws IOException {
        if (line == null || line.isBlank()) {
            return;
        }
        appendHistoryLogLine(events, line);
    }

    private void appendHistoryLogLine(List<StoredHistoryEvent> events, String line) throws IOException {
        events.add(mapper().readValue(line, StoredHistoryEvent.class));
    }

    private void appendHistory(WorkflowRunId runId, List<StoredHistoryEvent> events) {
        appendHistory(historyPath(runId), events);
    }

    private void appendHistory(WorkflowRunId runId, TenantId tenantId, List<StoredHistoryEvent> events) {
        appendHistory(historyPath(runId, tenantId), events);
    }

    private void appendHistory(Path historyPath, List<StoredHistoryEvent> events) {
        if (events.isEmpty()) {
            return;
        }
        FilePersistenceSupport.withFileLock(rootDirectory, historyPath, () -> {
            Path logPath = historyLogPath(historyPath);
            appendHistoryLog(logPath, events);
            compactHistoryLogIfNeeded(historyPath, logPath);
            return null;
        });
    }

    private void appendHistoryLog(Path historyLogPath, List<StoredHistoryEvent> events) {
        try {
            Files.createDirectories(historyLogPath.getParent());
            StringBuilder builder = new StringBuilder();
            for (StoredHistoryEvent event : events) {
                builder.append(mapper().writeValueAsString(event)).append('\n');
            }
            Files.writeString(
                    historyLogPath,
                    builder.toString(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    private void compactHistoryLogIfNeeded(Path historyPath, Path historyLogPath) {
        if (historyLogCompactionBytes <= 0) {
            return;
        }
        try {
            Path compactingPath = historyLogCompactingPath(historyLogPath);
            long pendingBytes = fileSize(historyLogPath) + fileSize(compactingPath);
            if (pendingBytes < historyLogCompactionBytes) {
                return;
            }
            StoredHistory history = readHistory(historyPath);
            FilePersistenceSupport.writeAtomic(
                    rootDirectory,
                    historyPath,
                    new StoredHistorySnapshot(history.events(), history.updatedAt()),
                    mapper());
            moveAsideBestEffort(historyLogPath, compactingPath);
            deleteBestEffort(historyLogPath);
            deleteBestEffort(compactingPath);
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    private long fileSize(Path path) throws IOException {
        return Files.isRegularFile(path) ? Files.size(path) : 0;
    }

    private void moveAsideBestEffort(Path source, Path target) throws IOException {
        if (!Files.isRegularFile(source)) {
            return;
        }
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void deleteBestEffort(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // Stale logs are harmless because history loading deduplicates event ids after compaction.
        }
    }

    private List<StoredHistoryEvent> toStoredEvents(List<ExecutionEvent> events) {
        return events.stream()
                .map(this::toStoredEvent)
                .toList();
    }

    private StoredHistoryEvent toStoredEvent(ExecutionEvent event) {
        return new StoredHistoryEvent(
                event.eventId(),
                safeEventType(event),
                eventMessage(event),
                event.occurredAt(),
                eventMetadata(event));
    }

    private String eventMessage(ExecutionEvent event) {
        if (event instanceof GenericExecutionEvent generic && generic.message() != null && !generic.message().isBlank()) {
            return generic.message();
        }
        if (event instanceof NodeFailedEvent failed && failed.error() != null && failed.error().message() != null) {
            return failed.error().message();
        }
        if (event instanceof WorkflowFailedEvent failed && failed.error() != null && failed.error().message() != null) {
            return failed.error().message();
        }
        if (event instanceof WorkflowSuspendedEvent suspended && suspended.reason() != null) {
            return suspended.reason();
        }
        if (event instanceof WorkflowCancelledEvent cancelled && cancelled.reason() != null) {
            return cancelled.reason();
        }
        return safeEventType(event);
    }

    private Map<String, Object> eventMetadata(ExecutionEvent event) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("source", "domain-event");
        metadata.put("domainEventType", safeEventType(event));
        metadata.put("domainEventClass", event.getClass().getSimpleName());
        if (event.runId() != null) {
            metadata.put("runId", event.runId().value());
        }
        if (event instanceof GenericExecutionEvent generic) {
            metadata.putAll(generic.metadata());
        } else if (event instanceof WorkflowStartedEvent started) {
            putValue(metadata, "definitionId", started.definitionId());
            putValue(metadata, "tenantId", started.tenantId());
            metadata.put("inputs", started.inputs());
        } else if (event instanceof NodeScheduledEvent scheduled) {
            putNode(metadata, scheduled.nodeId());
            metadata.put("attempt", scheduled.attempt());
        } else if (event instanceof NodeStartedEvent started) {
            putNode(metadata, started.nodeId());
            metadata.put("attempt", started.attempt());
        } else if (event instanceof NodeCompletedEvent completed) {
            putNode(metadata, completed.nodeId());
            metadata.put("attempt", completed.attempt());
            metadata.put("output", completed.output());
        } else if (event instanceof NodeFailedEvent failed) {
            putNode(metadata, failed.nodeId());
            metadata.put("attempt", failed.attempt());
            metadata.put("willRetry", failed.willRetry());
            putValue(metadata, "retryAt", failed.retryAt());
            metadata.put("error", errorPayload(failed.error()));
        } else if (event instanceof WorkflowSuspendedEvent suspended) {
            putValue(metadata, "reason", suspended.reason());
            putNode(metadata, "waitingOnNodeId", suspended.waitingOnNodeId());
        } else if (event instanceof WorkflowResumedEvent resumed) {
            putValue(metadata, "humanTaskId", resumed.humanTaskId());
            metadata.put("resumeData", resumed.resumeData());
        } else if (event instanceof WorkflowCompletedEvent completed) {
            metadata.put("outputs", completed.outputs());
        } else if (event instanceof WorkflowFailedEvent failed) {
            metadata.put("error", errorPayload(failed.error()));
        } else if (event instanceof CompensationStartedEvent started) {
            putValue(metadata, "tenantId", started.tenantId());
            metadata.put("nodesToCompensate", nodeValues(started.nodesToCompensate()));
        } else if (event instanceof CompensationCompletedEvent completed) {
            putValue(metadata, "tenantId", completed.tenantId());
            metadata.put("compensatedNodes", nodeValues(completed.compensatedNodes()));
        } else if (event instanceof CompensationFailedEvent failed) {
            putValue(metadata, "tenantId", failed.tenantId());
            metadata.put("error", errorPayload(failed.error()));
        }
        return metadata;
    }

    private String safeEventType(ExecutionEvent event) {
        return event.eventType() != null && !event.eventType().isBlank() ? event.eventType() : "Unknown";
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private Map<String, Object> errorPayload(ErrorInfo error) {
        if (error == null) {
            return Map.of();
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        putValue(payload, "code", error.code());
        putValue(payload, "message", error.message());
        putValue(payload, "stackTrace", error.stackTrace());
        payload.put("context", error.context());
        return payload;
    }

    private List<String> nodeValues(List<NodeId> nodeIds) {
        if (nodeIds == null || nodeIds.isEmpty()) {
            return List.of();
        }
        return nodeIds.stream().map(NodeId::value).toList();
    }

    private void putNode(Map<String, Object> metadata, NodeId nodeId) {
        putNode(metadata, "nodeId", nodeId);
    }

    private void putNode(Map<String, Object> metadata, String key, NodeId nodeId) {
        if (nodeId != null) {
            metadata.put(key, nodeId.value());
        }
    }

    private void putValue(Map<String, Object> metadata, String key, Object value) {
        if (value != null) {
            metadata.put(key, String.valueOf(value));
        }
    }

    private Path historyPath(WorkflowRunId runId) {
        return rootDirectory
                .resolve("history")
                .resolve(FilePersistenceSupport.fileName(runId.value()))
                .normalize();
    }

    private Path historyPath(WorkflowRunId runId, TenantId tenantId) {
        return tenantHistoryDirectory(tenantId)
                .resolve("events")
                .resolve(FilePersistenceSupport.fileName(runId.value()))
                .normalize();
    }

    private Path historyLogPath(Path historyPath) {
        String fileName = historyPath.getFileName().toString();
        String logFileName = fileName.endsWith(".json")
                ? fileName.substring(0, fileName.length() - ".json".length()) + ".jsonl"
                : fileName + ".jsonl";
        return historyPath.resolveSibling(logFileName);
    }

    private Path historyLogCompactingPath(Path historyLogPath) {
        return historyLogPath.resolveSibling(historyLogPath.getFileName() + COMPACTING_SUFFIX);
    }

    private Path processedNodePath(WorkflowRunId runId, NodeId nodeId, int attempt) {
        return rootDirectory
                .resolve("history")
                .resolve("processed-node-results")
                .resolve(FilePersistenceSupport.fileName(runId.value() + ":" + nodeId.value() + ":" + attempt))
                .normalize();
    }

    private Path processedNodePath(WorkflowRunId runId, TenantId tenantId, NodeId nodeId, int attempt) {
        return tenantHistoryDirectory(tenantId)
                .resolve("processed-node-results")
                .resolve(FilePersistenceSupport.fileName(runId.value() + ":" + nodeId.value() + ":" + attempt))
                .normalize();
    }

    private Path processedExternalSignalPath(WorkflowRunId runId, String idempotencyKey) {
        return rootDirectory
                .resolve("history")
                .resolve("processed-external-signals")
                .resolve(FilePersistenceSupport.fileName(runId.value() + ":" + idempotencyKey))
                .normalize();
    }

    private Path processedExternalSignalPath(WorkflowRunId runId, TenantId tenantId, String idempotencyKey) {
        return tenantHistoryDirectory(tenantId)
                .resolve("processed-external-signals")
                .resolve(FilePersistenceSupport.fileName(runId.value() + ":" + idempotencyKey))
                .normalize();
    }

    private Path processedCompensationNodePath(WorkflowRunId runId, NodeId nodeId) {
        return rootDirectory
                .resolve("history")
                .resolve("processed-compensation-nodes")
                .resolve(FilePersistenceSupport.fileName(runId.value() + ":" + nodeId.value()))
                .normalize();
    }

    private Path processedCompensationNodePath(WorkflowRunId runId, TenantId tenantId, NodeId nodeId) {
        return tenantHistoryDirectory(tenantId)
                .resolve("processed-compensation-nodes")
                .resolve(FilePersistenceSupport.fileName(runId.value() + ":" + nodeId.value()))
                .normalize();
    }

    private Path tenantHistoryDirectory(TenantId tenantId) {
        Objects.requireNonNull(tenantId, "TenantId cannot be null");
        return rootDirectory
                .resolve("history")
                .resolve("tenants")
                .resolve(FilePersistenceSupport.directoryName(tenantId.value()))
                .normalize();
    }

    private ObjectMapper mapper() {
        return objectMapper != null ? objectMapper : fallbackObjectMapper;
    }

    private record StoredHistory(List<StoredHistoryEvent> events, Instant updatedAt, boolean hasPersistentSource) {
        StoredHistory {
            events = events != null ? List.copyOf(events) : List.of();
            updatedAt = updatedAt != null ? updatedAt : Instant.now();
        }

    }

    private record StoredHistorySnapshot(List<StoredHistoryEvent> events, Instant updatedAt) {
        StoredHistorySnapshot {
            events = events != null ? List.copyOf(events) : List.of();
            updatedAt = updatedAt != null ? updatedAt : Instant.now();
        }
    }

    private record StoredHistoryEvent(
            String eventId,
            String type,
            String message,
            Instant occurredAt,
            Map<String, Object> metadata) {
        StoredHistoryEvent {
            metadata = metadata != null ? Collections.unmodifiableMap(new HashMap<>(metadata)) : Map.of();
            occurredAt = occurredAt != null ? occurredAt : Instant.now();
        }
    }

    private record ProcessedNodeResult(
            WorkflowRunId runId,
            TenantId tenantId,
            NodeId nodeId,
            int attempt,
            Instant processedAt) {
    }

    private record ProcessedExternalSignal(
            WorkflowRunId runId,
            TenantId tenantId,
            String idempotencyKey,
            Instant processedAt) {
    }

    private record ProcessedCompensationNode(
            WorkflowRunId runId,
            TenantId tenantId,
            NodeId nodeId,
            Instant processedAt) {
    }
}
