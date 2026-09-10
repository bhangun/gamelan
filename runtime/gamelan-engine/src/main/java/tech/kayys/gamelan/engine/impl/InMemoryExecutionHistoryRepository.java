package tech.kayys.gamelan.engine.impl;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import io.quarkus.arc.DefaultBean;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import tech.kayys.gamelan.engine.ExecutionEventTypes;
import tech.kayys.gamelan.engine.node.NodeId;
import tech.kayys.gamelan.engine.tenant.TenantId;
import tech.kayys.gamelan.engine.workflow.WorkflowRunId;
import tech.kayys.gamelan.engine.event.ExecutionEvent;
import tech.kayys.gamelan.engine.event.GenericExecutionEvent;
import tech.kayys.gamelan.engine.execution.ExecutionEventEnvelopes;
import tech.kayys.gamelan.engine.execution.ExecutionHistory;
import tech.kayys.gamelan.engine.execution.ExecutionHistoryRepository;

@ApplicationScoped
@DefaultBean
public class InMemoryExecutionHistoryRepository implements ExecutionHistoryRepository {

    private final Map<HistoryKey, HistoryBuffer> events = new ConcurrentHashMap<>();
    private final Set<ProcessedNodeKey> processedNodeKeys = ConcurrentHashMap.newKeySet();
    private final Set<ProcessedSignalKey> processedSignalKeys = ConcurrentHashMap.newKeySet();
    private final Set<ProcessedSignalKey> signalAuditKeys = ConcurrentHashMap.newKeySet();
    private final Set<ProcessedSignalKey> ignoredSignalAuditKeys = ConcurrentHashMap.newKeySet();
    private final Set<ProcessedCompensationNodeKey> processedCompensationNodeKeys = ConcurrentHashMap.newKeySet();

    @Override
    public Uni<Void> append(
            WorkflowRunId runId,
            String type,
            String message,
            Map<String, Object> metadata) {
        buffer(runId, null)
                .add(new GenericExecutionEvent(runId, type, message, Instant.now(), metadata));
        return Uni.createFrom().voidItem();
    }

    @Override
    public Uni<Void> append(
            WorkflowRunId runId,
            TenantId tenantId,
            String type,
            String message,
            Map<String, Object> metadata) {
        buffer(runId, tenantId)
                .add(new GenericExecutionEvent(runId, type, message, Instant.now(), metadata));
        return Uni.createFrom().voidItem();
    }

    @Override
    public Uni<Void> appendEvents(WorkflowRunId runId, List<ExecutionEvent> runEvents) {
        List<ExecutionEvent> safeEvents = ExecutionEventEnvelopes.validateForRun(runId, runEvents);
        if (!safeEvents.isEmpty()) {
            buffer(runId, null).addAll(safeEvents);
        }
        return Uni.createFrom().voidItem();
    }

    @Override
    public Uni<Void> appendEvents(WorkflowRunId runId, TenantId tenantId, List<ExecutionEvent> runEvents) {
        List<ExecutionEvent> safeEvents = ExecutionEventEnvelopes.validateForRun(runId, tenantId, runEvents);
        if (!safeEvents.isEmpty()) {
            buffer(runId, tenantId).addAll(safeEvents);
        }
        return Uni.createFrom().voidItem();
    }

    @Override
    public Uni<ExecutionHistory> load(WorkflowRunId runId) {
        List<ExecutionEvent> runEvents = snapshot(runId, null);
        return Uni.createFrom().item(
                ExecutionHistory.fromEvents(runId, runEvents));
    }

    @Override
    public Uni<ExecutionHistory> load(WorkflowRunId runId, TenantId tenantId) {
        List<ExecutionEvent> runEvents = snapshot(runId, tenantId);
        if (runEvents.isEmpty()) {
            runEvents = snapshot(runId, null);
        }
        return Uni.createFrom().item(
                ExecutionHistory.fromEvents(runId, runEvents));
    }

    @Override
    public Uni<Boolean> isNodeResultProcessed(
            WorkflowRunId runId,
            NodeId nodeId,
            int attempt) {
        return Uni.createFrom().item(processedNodeKeys.contains(processedNodeKey(runId, nodeId, attempt)));
    }

    @Override
    public Uni<Boolean> isNodeResultProcessed(
            WorkflowRunId runId,
            TenantId tenantId,
            NodeId nodeId,
            int attempt) {
        return Uni.createFrom().item(() -> processedNodeKeys.contains(processedNodeKey(runId, nodeId, attempt))
                || processedNodeKeys.contains(processedNodeKey(runId, tenantId, nodeId, attempt)));
    }

    @Override
    public Uni<Boolean> markNodeResultProcessed(
            WorkflowRunId runId,
            NodeId nodeId,
            int attempt) {
        return Uni.createFrom().item(processedNodeKeys.add(processedNodeKey(runId, nodeId, attempt)));
    }

    @Override
    public Uni<Boolean> markNodeResultProcessed(
            WorkflowRunId runId,
            TenantId tenantId,
            NodeId nodeId,
            int attempt) {
        return Uni.createFrom().item(() -> {
            if (tenantId == null) {
                return processedNodeKeys.add(processedNodeKey(runId, nodeId, attempt));
            }
            if (processedNodeKeys.contains(processedNodeKey(runId, nodeId, attempt))) {
                return false;
            }
            return processedNodeKeys.add(processedNodeKey(runId, tenantId, nodeId, attempt));
        });
    }

    @Override
    public Uni<Boolean> isExternalSignalProcessed(WorkflowRunId runId, String idempotencyKey) {
        if (isBlank(idempotencyKey)) {
            return Uni.createFrom().item(false);
        }
        return Uni.createFrom().item(processedSignalKeys.contains(processedSignalKey(runId, idempotencyKey)));
    }

    @Override
    public Uni<Boolean> isExternalSignalProcessed(
            WorkflowRunId runId,
            TenantId tenantId,
            String idempotencyKey) {
        if (tenantId == null) {
            return isExternalSignalProcessed(runId, idempotencyKey);
        }
        if (isBlank(idempotencyKey)) {
            return Uni.createFrom().item(false);
        }
        return Uni.createFrom().item(() -> processedSignalKeys.contains(processedSignalKey(runId, idempotencyKey))
                || processedSignalKeys.contains(processedSignalKey(runId, tenantId, idempotencyKey)));
    }

    @Override
    public Uni<Boolean> markExternalSignalProcessed(WorkflowRunId runId, String idempotencyKey) {
        if (isBlank(idempotencyKey)) {
            return Uni.createFrom().item(false);
        }
        return Uni.createFrom().item(processedSignalKeys.add(processedSignalKey(runId, idempotencyKey)));
    }

    @Override
    public Uni<Boolean> markExternalSignalProcessed(
            WorkflowRunId runId,
            TenantId tenantId,
            String idempotencyKey) {
        if (tenantId == null) {
            return markExternalSignalProcessed(runId, idempotencyKey);
        }
        if (isBlank(idempotencyKey)) {
            return Uni.createFrom().item(false);
        }
        return Uni.createFrom().item(() -> {
            if (processedSignalKeys.contains(processedSignalKey(runId, idempotencyKey))) {
                return false;
            }
            return processedSignalKeys.add(processedSignalKey(runId, tenantId, idempotencyKey));
        });
    }

    @Override
    public Uni<Boolean> appendSignalReceivedAudit(
            WorkflowRunId runId,
            TenantId tenantId,
            String idempotencyKey,
            String signalName,
            Map<String, Object> metadata) {
        if (isBlank(idempotencyKey)) {
            return Uni.createFrom().item(false);
        }
        return Uni.createFrom().item(() -> {
            ProcessedSignalKey legacyKey = processedSignalKey(runId, idempotencyKey);
            ProcessedSignalKey signalAuditKey = tenantId != null
                    ? processedSignalKey(runId, tenantId, idempotencyKey)
                    : legacyKey;
            if (tenantId != null && signalAuditKeys.contains(legacyKey)) {
                return false;
            }
            boolean inserted = signalAuditKeys.add(signalAuditKey);
            if (inserted) {
                buffer(runId, tenantId).add(new GenericExecutionEvent(
                        runId,
                        ExecutionEventTypes.SIGNAL_RECEIVED,
                        signalName,
                        Instant.now(),
                        metadata));
            }
            return inserted;
        });
    }

    @Override
    public Uni<Boolean> appendSignalIgnoredAudit(
            WorkflowRunId runId,
            TenantId tenantId,
            String idempotencyKey,
            String reason,
            Map<String, Object> metadata) {
        if (isBlank(idempotencyKey)) {
            return Uni.createFrom().item(false);
        }
        return Uni.createFrom().item(() -> {
            ProcessedSignalKey legacyKey = processedSignalKey(runId, idempotencyKey);
            ProcessedSignalKey ignoredAuditKey = tenantId != null
                    ? processedSignalKey(runId, tenantId, idempotencyKey)
                    : legacyKey;
            if (tenantId != null && ignoredSignalAuditKeys.contains(legacyKey)) {
                return false;
            }
            boolean inserted = ignoredSignalAuditKeys.add(ignoredAuditKey);
            if (inserted) {
                buffer(runId, tenantId).add(new GenericExecutionEvent(
                        runId,
                        ExecutionEventTypes.SIGNAL_IGNORED,
                        reason,
                        Instant.now(),
                        metadata));
            }
            return inserted;
        });
    }

    @Override
    public Uni<Boolean> isCompensationNodeProcessed(WorkflowRunId runId, NodeId nodeId) {
        return Uni.createFrom().item(processedCompensationNodeKeys.contains(compensationNodeKey(runId, nodeId)));
    }

    @Override
    public Uni<Boolean> isCompensationNodeProcessed(WorkflowRunId runId, TenantId tenantId, NodeId nodeId) {
        if (tenantId == null) {
            return isCompensationNodeProcessed(runId, nodeId);
        }
        return Uni.createFrom().item(() -> processedCompensationNodeKeys.contains(compensationNodeKey(runId, nodeId))
                || processedCompensationNodeKeys.contains(compensationNodeKey(runId, tenantId, nodeId)));
    }

    @Override
    public Uni<Boolean> markCompensationNodeProcessed(WorkflowRunId runId, NodeId nodeId) {
        return Uni.createFrom().item(processedCompensationNodeKeys.add(compensationNodeKey(runId, nodeId)));
    }

    @Override
    public Uni<Boolean> markCompensationNodeProcessed(WorkflowRunId runId, TenantId tenantId, NodeId nodeId) {
        if (tenantId == null) {
            return markCompensationNodeProcessed(runId, nodeId);
        }
        return Uni.createFrom().item(() -> {
            if (processedCompensationNodeKeys.contains(compensationNodeKey(runId, nodeId))) {
                return false;
            }
            return processedCompensationNodeKeys.add(compensationNodeKey(runId, tenantId, nodeId));
        });
    }

    private HistoryBuffer buffer(WorkflowRunId runId, TenantId tenantId) {
        return events.computeIfAbsent(historyKey(runId, tenantId), ignored -> new HistoryBuffer());
    }

    private List<ExecutionEvent> snapshot(WorkflowRunId runId, TenantId tenantId) {
        HistoryBuffer buffer = events.get(historyKey(runId, tenantId));
        return buffer != null ? buffer.snapshot() : List.of();
    }

    private static HistoryKey historyKey(WorkflowRunId runId, TenantId tenantId) {
        return new HistoryKey(runId, tenantId);
    }

    private static ProcessedNodeKey processedNodeKey(WorkflowRunId runId, NodeId nodeId, int attempt) {
        return new ProcessedNodeKey(runId, null, nodeId, attempt);
    }

    private static ProcessedNodeKey processedNodeKey(WorkflowRunId runId, TenantId tenantId, NodeId nodeId, int attempt) {
        return new ProcessedNodeKey(runId, tenantId, nodeId, attempt);
    }

    private static ProcessedSignalKey processedSignalKey(WorkflowRunId runId, String idempotencyKey) {
        return new ProcessedSignalKey(runId, null, idempotencyKey);
    }

    private static ProcessedSignalKey processedSignalKey(WorkflowRunId runId, TenantId tenantId, String idempotencyKey) {
        return new ProcessedSignalKey(runId, tenantId, idempotencyKey);
    }

    private static ProcessedCompensationNodeKey compensationNodeKey(WorkflowRunId runId, NodeId nodeId) {
        return new ProcessedCompensationNodeKey(runId, null, nodeId);
    }

    private static ProcessedCompensationNodeKey compensationNodeKey(
            WorkflowRunId runId,
            TenantId tenantId,
            NodeId nodeId) {
        return new ProcessedCompensationNodeKey(runId, tenantId, nodeId);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static final class HistoryBuffer {
        private final List<ExecutionEvent> events = new ArrayList<>();

        synchronized void add(ExecutionEvent event) {
            if (event != null) {
                events.add(event);
            }
        }

        synchronized void addAll(List<ExecutionEvent> newEvents) {
            events.addAll(newEvents);
        }

        synchronized List<ExecutionEvent> snapshot() {
            return List.copyOf(events);
        }
    }

    private record HistoryKey(WorkflowRunId runId, TenantId tenantId) {
    }

    private record ProcessedNodeKey(WorkflowRunId runId, TenantId tenantId, NodeId nodeId, int attempt) {
    }

    private record ProcessedSignalKey(WorkflowRunId runId, TenantId tenantId, String idempotencyKey) {
    }

    private record ProcessedCompensationNodeKey(WorkflowRunId runId, TenantId tenantId, NodeId nodeId) {
    }
}
