package tech.kayys.gamelan.runtime.repository;

import io.quarkus.arc.DefaultBean;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import tech.kayys.gamelan.engine.callback.CallbackRegistration;
import tech.kayys.gamelan.engine.error.ErrorCode;
import tech.kayys.gamelan.engine.error.ErrorInfo;
import tech.kayys.gamelan.engine.error.ErrorSnapshot;
import tech.kayys.gamelan.engine.error.GamelanException;
import tech.kayys.gamelan.engine.execution.BearerTokenHash;
import tech.kayys.gamelan.engine.execution.ExecutionToken;
import tech.kayys.gamelan.engine.execution.ExecutionTokenHash;
import tech.kayys.gamelan.engine.node.NodeExecution;
import tech.kayys.gamelan.engine.node.NodeExecutionSnapshot;
import tech.kayys.gamelan.engine.node.NodeExecutionStatus;
import tech.kayys.gamelan.engine.node.NodeId;
import tech.kayys.gamelan.engine.repository.WorkflowRunRepository;
import tech.kayys.gamelan.engine.repository.WorkflowRunRecoveryCursor;
import tech.kayys.gamelan.engine.repository.WorkflowRunRecoveryPage;
import tech.kayys.gamelan.engine.run.RunStatus;
import tech.kayys.gamelan.engine.tenant.TenantId;
import tech.kayys.gamelan.engine.workflow.WorkflowDefinitionId;
import tech.kayys.gamelan.engine.workflow.WorkflowRun;
import tech.kayys.gamelan.engine.workflow.WorkflowRunId;
import tech.kayys.gamelan.engine.workflow.WorkflowRunSnapshot;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

@ApplicationScoped
@DefaultBean
public class InMemoryWorkflowRunRepository implements WorkflowRunRepository {

    private final Map<String, WorkflowRun> runs = new ConcurrentHashMap<>();
    private final Map<String, StoredExecutionToken> tokens = new ConcurrentHashMap<>();
    private final Map<String, StoredCallbackRegistration> callbacks = new ConcurrentHashMap<>();
    private final Map<String, Object> runLocks = new ConcurrentHashMap<>();

    @Override
    public Uni<WorkflowRun> persist(WorkflowRun run) {
        synchronized (lock(run.getId())) {
            runs.put(run.getId().value(), run);
        }
        return Uni.createFrom().item(run);
    }

    @Override
    public Uni<WorkflowRun> update(WorkflowRun run) {
        return persist(run);
    }

    @Override
    public Uni<WorkflowRun> findById(WorkflowRunId id) {
        return Uni.createFrom().item(runs.get(id.value()));
    }

    @Override
    public Uni<WorkflowRun> findById(WorkflowRunId id, TenantId tenantId) {
        WorkflowRun run = runs.get(id.value());
        if (run != null && run.getTenantId().equals(tenantId)) {
            return Uni.createFrom().item(run);
        }
        return Uni.createFrom().nullItem();
    }

    @Override
    public <T> Uni<T> withLock(WorkflowRunId runId, Function<WorkflowRun, Uni<T>> action) {
        synchronized (lock(runId)) {
            try {
                WorkflowRun run = runs.get(runId.value());
                if (run == null) {
                    return Uni.createFrom().failure(
                            new NoSuchElementException("WorkflowRun not found: " + runId.value()));
                }
                T result = action.apply(run).await().indefinitely();
                return uniItem(result);
            } catch (Throwable error) {
                return Uni.createFrom().failure(error);
            }
        }
    }

    @Override
    public Uni<WorkflowRunSnapshot> snapshot(WorkflowRunId runId, TenantId tenantId) {
        return findById(runId, tenantId).map(run -> run != null ? run.createSnapshot() : null);
    }

    @Override
    public Uni<List<WorkflowRun>> query(TenantId tenantId, WorkflowDefinitionId definitionId, RunStatus status, int page,
            int size) {
        return Uni.createFrom().item(runs.values().stream()
                .filter(r -> r.getTenantId().equals(tenantId))
                .filter(r -> definitionId == null || r.getDefinitionId().equals(definitionId))
                .filter(r -> status == null || r.getStatus() == status)
                .skip((long) page * size)
                .limit(size > 0 ? size : Long.MAX_VALUE)
                .toList());
    }

    @Override
    public Uni<List<WorkflowRun>> queryActiveRunsForRecovery(int page, int size) {
        int safePage = Math.max(0, page);
        int safeSize = size > 0 ? size : 100;
        return Uni.createFrom().item(activeRecoveryRuns().stream()
                .skip((long) safePage * safeSize)
                .limit(safeSize)
                .toList());
    }

    @Override
    public Uni<WorkflowRunRecoveryPage> scanActiveRunsForRecovery(WorkflowRunRecoveryCursor cursor, int size) {
        WorkflowRunRecoveryCursor safeCursor = cursor != null ? cursor : WorkflowRunRecoveryCursor.start();
        int safeSize = size > 0 ? size : 100;
        return Uni.createFrom().item(() -> {
            List<WorkflowRun> page = activeRecoveryRuns().stream()
                    .filter(run -> !safeCursor.hasAfterRunId()
                            || run.getId().value().compareTo(safeCursor.afterRunId()) > 0)
                    .limit((long) safeSize + 1)
                    .toList();
            return WorkflowRunRecoveryPage.keyset(page, safeSize);
        });
    }

    @Override
    public Uni<Long> countActiveRuns(TenantId tenantId) {
        return Uni.createFrom().item(runs.values().stream()
                .filter(r -> r.getTenantId().equals(tenantId))
                .filter(r -> r.getStatus().isActive())
                .count());
    }

    private List<WorkflowRun> activeRecoveryRuns() {
        return runs.values().stream()
                .filter(run -> run.getStatus() != null && run.getStatus().isActive())
                .sorted(Comparator.comparing(run -> run.getId().value()))
                .toList();
    }

    @Override
    public Uni<Void> storeToken(ExecutionToken token) {
        pruneExpiredTokens();
        tokens.put(ExecutionTokenHash.sha256(token.value()), StoredExecutionToken.from(token));
        return Uni.createFrom().voidItem();
    }

    @Override
    public Uni<Boolean> validateToken(ExecutionToken token) {
        if (token == null) {
            return Uni.createFrom().item(false);
        }

        String tokenHash = ExecutionTokenHash.sha256(token.value());
        StoredExecutionToken stored = tokens.get(tokenHash);
        if (stored == null) {
            return Uni.createFrom().item(false);
        }
        if (stored.isExpired()) {
            tokens.remove(tokenHash);
            return Uni.createFrom().item(false);
        }
        return Uni.createFrom().item(stored.matches(token));
    }

    @Override
    public Uni<Void> storeCallback(CallbackRegistration callback) {
        pruneExpiredCallbacks();
        callbacks.put(BearerTokenHash.sha256(callback.callbackToken()), StoredCallbackRegistration.from(callback));
        return Uni.createFrom().voidItem();
    }

    @Override
    public Uni<Boolean> validateCallback(WorkflowRunId runId, String token) {
        if (runId == null || token == null || token.isBlank()) {
            return Uni.createFrom().item(false);
        }

        String tokenHash = BearerTokenHash.sha256(token);
        StoredCallbackRegistration stored = callbacks.get(tokenHash);
        if (stored == null) {
            return Uni.createFrom().item(false);
        }
        if (stored.isExpired()) {
            callbacks.remove(tokenHash);
            return Uni.createFrom().item(false);
        }
        return Uni.createFrom().item(stored.runId().equals(runId));
    }

    @Override
    public Uni<Boolean> validateCallback(WorkflowRunId runId, TenantId tenantId, String token) {
        if (runId == null || token == null || token.isBlank()) {
            return Uni.createFrom().item(false);
        }

        String tokenHash = BearerTokenHash.sha256(token);
        StoredCallbackRegistration stored = callbacks.get(tokenHash);
        if (stored == null) {
            return Uni.createFrom().item(false);
        }
        if (stored.isExpired()) {
            callbacks.remove(tokenHash);
            return Uni.createFrom().item(false);
        }
        return Uni.createFrom().item(stored.runId().equals(runId) && stored.tenantMatches(tenantId));
    }

    @Override
    public Uni<Void> updateContextVariable(WorkflowRunId runId, String key, Object value) {
        synchronized (lock(runId)) {
            WorkflowRun run = runs.get(runId.value());
            if (run != null) {
                run.getContext().setVariable(key, value);
            }
        }
        return Uni.createFrom().voidItem();
    }

    @Override
    public Uni<Void> updateNodeExecution(WorkflowRunId runId, NodeId nodeId, NodeExecutionSnapshot snapshot) {
        synchronized (lock(runId)) {
            WorkflowRun run = runs.get(runId.value());
            if (run != null) {
                updateExistingNodeExecution(run, nodeId, snapshot);
            }
        }
        return Uni.createFrom().voidItem();
    }

    private void updateExistingNodeExecution(WorkflowRun run, NodeId nodeId, NodeExecutionSnapshot snapshot) {
        if (snapshot == null) {
            return;
        }
        NodeExecution execution;
        try {
            execution = run.getNodeExecution(nodeId);
        } catch (GamelanException error) {
            if (error.getErrorCode() != ErrorCode.TASK_NOT_FOUND) {
                throw error;
            }
            return;
        }
        execution.setStatus(toStatus(snapshot.status()));
        execution.setAttempt(snapshot.attempt() > 0 ? snapshot.attempt() : execution.getAttempt());
        execution.setStartedAt(snapshot.startedAt());
        execution.setCompletedAt(snapshot.completedAt());
        execution.setRetryAt(snapshot.retryAt());
        execution.setOutput(snapshot.output());
        execution.setLastError(toErrorInfo(snapshot.error()));
    }

    private NodeExecutionStatus toStatus(String status) {
        if (status == null || status.isBlank()) {
            return NodeExecutionStatus.PENDING;
        }
        return NodeExecutionStatus.valueOf(status);
    }

    private ErrorInfo toErrorInfo(ErrorSnapshot error) {
        if (error == null) {
            return null;
        }
        return new ErrorInfo(error.code(), error.message(), error.stackTrace(), Map.of());
    }

    private Object lock(WorkflowRunId runId) {
        return runLocks.computeIfAbsent(runId.value(), ignored -> new Object());
    }

    private void pruneExpiredTokens() {
        tokens.entrySet().removeIf(entry -> entry.getValue().isExpired());
    }

    private void pruneExpiredCallbacks() {
        callbacks.entrySet().removeIf(entry -> entry.getValue().isExpired());
    }

    @SuppressWarnings("unchecked")
    private <T> Uni<T> uniItem(T value) {
        return value != null ? Uni.createFrom().item(value) : (Uni<T>) Uni.createFrom().nullItem();
    }

    private record StoredExecutionToken(
            WorkflowRunId runId,
            TenantId tenantId,
            tech.kayys.gamelan.engine.node.NodeId nodeId,
            int attempt,
            Instant expiresAt) {

        static StoredExecutionToken from(ExecutionToken token) {
            return new StoredExecutionToken(
                    token.runId(),
                    token.tenantId(),
                    token.nodeId(),
                    token.attempt(),
                    token.expiresAt());
        }

        boolean isExpired() {
            return Instant.now().isAfter(expiresAt);
        }

        boolean matches(ExecutionToken token) {
            return runId.equals(token.runId())
                    && tenantMatches(token)
                    && nodeId.equals(token.nodeId())
                    && attempt == token.attempt();
        }

        private boolean tenantMatches(ExecutionToken token) {
            return tenantId == null || tenantId.equals(token.tenantId());
        }
    }

    private record StoredCallbackRegistration(
            WorkflowRunId runId,
            TenantId tenantId,
            Instant expiresAt) {

        static StoredCallbackRegistration from(CallbackRegistration callback) {
            return new StoredCallbackRegistration(callback.runId(), callback.tenantId(), callback.expiresAt());
        }

        boolean isExpired() {
            return Instant.now().isAfter(expiresAt);
        }

        boolean tenantMatches(TenantId expectedTenantId) {
            return tenantId == null || tenantId.equals(expectedTenantId);
        }
    }
}
