package tech.kayys.gamelan.runtime.repository;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Collections;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Stream;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.quarkus.arc.properties.IfBuildProperty;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import tech.kayys.gamelan.engine.callback.CallbackRegistration;
import tech.kayys.gamelan.engine.error.ErrorCode;
import tech.kayys.gamelan.engine.error.ErrorInfo;
import tech.kayys.gamelan.engine.error.ErrorSnapshot;
import tech.kayys.gamelan.engine.error.GamelanException;
import tech.kayys.gamelan.engine.execution.BearerTokenHash;
import tech.kayys.gamelan.engine.execution.ExecutionToken;
import tech.kayys.gamelan.engine.execution.ExecutionTokenHash;
import tech.kayys.gamelan.engine.node.NodeDefinition;
import tech.kayys.gamelan.engine.node.NodeExecution;
import tech.kayys.gamelan.engine.node.NodeExecutionSnapshot;
import tech.kayys.gamelan.engine.node.NodeExecutionStatus;
import tech.kayys.gamelan.engine.node.NodeId;
import tech.kayys.gamelan.engine.repository.WorkflowDefinitionRepository;
import tech.kayys.gamelan.engine.repository.WorkflowRunRepository;
import tech.kayys.gamelan.engine.repository.WorkflowRunRecoveryCursor;
import tech.kayys.gamelan.engine.repository.WorkflowRunRecoveryPage;
import tech.kayys.gamelan.engine.run.RunStatus;
import tech.kayys.gamelan.engine.run.SuspensionInfo;
import tech.kayys.gamelan.engine.saga.CompensationState;
import tech.kayys.gamelan.engine.signal.Signal;
import tech.kayys.gamelan.engine.tenant.TenantId;
import tech.kayys.gamelan.engine.workflow.WorkflowDefinition;
import tech.kayys.gamelan.engine.workflow.WorkflowDefinitionId;
import tech.kayys.gamelan.engine.workflow.WorkflowRun;
import tech.kayys.gamelan.engine.workflow.WorkflowRunId;
import tech.kayys.gamelan.engine.workflow.WorkflowRunSnapshot;

@ApplicationScoped
@IfBuildProperty(name = "gamelan.workflow.persistence.store", stringValue = "file")
public class FileWorkflowRunRepository implements WorkflowRunRepository {

    private final Path rootDirectory;
    private final ObjectMapper fallbackObjectMapper;

    @Inject
    ObjectMapper objectMapper;

    @Inject
    WorkflowDefinitionRepository definitionRepository;

    @Inject
    public FileWorkflowRunRepository(
            @ConfigProperty(name = "gamelan.workflow.persistence.file.root", defaultValue = ".gamelan/workflow")
            String rootDirectory) {
        this(FilePersistenceSupport.root(rootDirectory), FilePersistenceSupport.objectMapper(), null);
    }

    public FileWorkflowRunRepository(Path rootDirectory, WorkflowDefinitionRepository definitionRepository) {
        this(rootDirectory.toAbsolutePath().normalize(), FilePersistenceSupport.objectMapper(), definitionRepository);
    }

    FileWorkflowRunRepository(
            Path rootDirectory,
            ObjectMapper objectMapper,
            WorkflowDefinitionRepository definitionRepository) {
        this.rootDirectory = Objects.requireNonNull(rootDirectory, "rootDirectory").toAbsolutePath().normalize();
        this.fallbackObjectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.definitionRepository = definitionRepository;
    }

    @Override
    public Uni<WorkflowRun> persist(WorkflowRun run) {
        return Uni.createFrom().item(() -> {
            FilePersistenceSupport.withFileLock(rootDirectory, runPath(run.getId(), run.getTenantId()), () -> {
                writeRun(toStoredRun(run));
                return null;
            });
            return run;
        });
    }

    @Override
    public Uni<WorkflowRun> update(WorkflowRun run) {
        return persist(run);
    }

    @Override
    public Uni<WorkflowRun> findById(WorkflowRunId id) {
        return Uni.createFrom().item(() -> {
            StoredRun stored = readRun(id);
            return stored != null ? toDomain(stored) : null;
        });
    }

    @Override
    public Uni<WorkflowRun> findById(WorkflowRunId id, TenantId tenantId) {
        return Uni.createFrom().item(() -> {
            StoredRun stored = readRun(id, tenantId);
            return stored != null ? toDomain(stored) : null;
        });
    }

    @Override
    public <T> Uni<T> withLock(WorkflowRunId runId, Function<WorkflowRun, Uni<T>> action) {
        return Uni.createFrom().deferred(() -> {
            try {
                StoredRun candidate = readRun(runId);
                if (candidate == null) {
                    return Uni.createFrom().failure(new NoSuchElementException(
                            "WorkflowRun not found: " + runId.value()));
                }
                return withTenantLock(candidate.id(), candidate.tenantId(), action, true);
            } catch (Throwable error) {
                return Uni.createFrom().failure(error);
            }
        });
    }

    @Override
    public <T> Uni<T> withLock(WorkflowRunId runId, TenantId tenantId, Function<WorkflowRun, Uni<T>> action) {
        return Uni.createFrom().deferred(() -> withTenantLock(runId, tenantId, action, false));
    }

    @Override
    public Uni<WorkflowRunSnapshot> snapshot(WorkflowRunId runId, TenantId tenantId) {
        return findById(runId, tenantId).map(run -> run != null ? run.createSnapshot() : null);
    }

    @Override
    public Uni<List<WorkflowRun>> query(
            TenantId tenantId,
            WorkflowDefinitionId definitionId,
            RunStatus status,
            int page,
            int size) {
        return Uni.createFrom().item(() -> listRuns(tenantId).stream()
                .filter(stored -> definitionId == null || definitionId.equals(stored.definitionId()))
                .filter(stored -> status == null || status == stored.status())
                .skip(Math.max(0, (long) page * size))
                .limit(size > 0 ? size : Long.MAX_VALUE)
                .map(this::toDomain)
                .toList());
    }

    @Override
    public Uni<List<WorkflowRun>> queryActiveRunsForRecovery(int page, int size) {
        return Uni.createFrom().item(() -> {
            int safePage = Math.max(0, page);
            int safeSize = size > 0 ? size : 100;
            return activeRecoveryRuns().stream()
                    .skip((long) safePage * safeSize)
                    .limit(safeSize)
                    .map(this::toDomain)
                    .toList();
        });
    }

    @Override
    public Uni<WorkflowRunRecoveryPage> scanActiveRunsForRecovery(WorkflowRunRecoveryCursor cursor, int size) {
        return Uni.createFrom().item(() -> {
            WorkflowRunRecoveryCursor safeCursor = cursor != null ? cursor : WorkflowRunRecoveryCursor.start();
            int safeSize = size > 0 ? size : 100;
            List<WorkflowRun> page = activeRecoveryRuns().stream()
                    .filter(stored -> !safeCursor.hasAfterRunId()
                            || stored.id().value().compareTo(safeCursor.afterRunId()) > 0)
                    .limit((long) safeSize + 1)
                    .map(this::toDomain)
                    .toList();
            return WorkflowRunRecoveryPage.keyset(page, safeSize);
        });
    }

    @Override
    public Uni<Long> countActiveRuns(TenantId tenantId) {
        return Uni.createFrom().item(() -> listRuns(tenantId).stream()
                .filter(stored -> stored.status() != null && stored.status().isActive())
                .count());
    }

    @Override
    public Uni<Void> storeToken(ExecutionToken token) {
        return Uni.createFrom().voidItem().invoke(() -> {
            pruneExpiredExecutionTokens();
            StoredExecutionToken stored = StoredExecutionToken.from(token);
            FilePersistenceSupport.writeAtomic(rootDirectory, executionTokenPath(stored.tokenHash()), stored, mapper());
        });
    }

    @Override
    public Uni<Boolean> validateToken(ExecutionToken token) {
        return Uni.createFrom().item(() -> {
            if (token == null) {
                return false;
            }
            Path path = executionTokenPath(ExecutionTokenHash.sha256(token.value()));
            StoredExecutionToken stored = FilePersistenceSupport.read(
                    path,
                    StoredExecutionToken.class,
                    mapper());
            if (stored == null) {
                return false;
            }
            if (stored.isExpired()) {
                FilePersistenceSupport.deleteIfExists(rootDirectory, path);
                return false;
            }
            return stored.matches(token);
        });
    }

    @Override
    public Uni<Void> storeCallback(CallbackRegistration callback) {
        return Uni.createFrom().voidItem().invoke(() -> {
            pruneExpiredCallbacks();
            StoredCallbackRegistration stored = StoredCallbackRegistration.from(callback);
            FilePersistenceSupport.writeAtomic(rootDirectory, callbackPath(stored.tokenHash()), stored, mapper());
        });
    }

    @Override
    public Uni<Boolean> validateCallback(WorkflowRunId runId, String token) {
        return Uni.createFrom().item(() -> {
            if (runId == null || token == null || token.isBlank()) {
                return false;
            }
            Path path = callbackPath(BearerTokenHash.sha256(token));
            StoredCallbackRegistration stored = FilePersistenceSupport.read(
                    path,
                    StoredCallbackRegistration.class,
                    mapper());
            if (stored == null) {
                return false;
            }
            if (stored.isExpired()) {
                FilePersistenceSupport.deleteIfExists(rootDirectory, path);
                return false;
            }
            return stored.runId().equals(runId);
        });
    }

    @Override
    public Uni<Boolean> validateCallback(WorkflowRunId runId, TenantId tenantId, String token) {
        return Uni.createFrom().item(() -> {
            if (runId == null || token == null || token.isBlank()) {
                return false;
            }
            Path path = callbackPath(BearerTokenHash.sha256(token));
            StoredCallbackRegistration stored = FilePersistenceSupport.read(
                    path,
                    StoredCallbackRegistration.class,
                    mapper());
            if (stored == null) {
                return false;
            }
            if (stored.isExpired()) {
                FilePersistenceSupport.deleteIfExists(rootDirectory, path);
                return false;
            }
            return stored.runId().equals(runId) && stored.tenantMatches(tenantId);
        });
    }

    @Override
    public Uni<Void> updateContextVariable(WorkflowRunId runId, String key, Object value) {
        return Uni.createFrom().voidItem().invoke(() -> {
            StoredRun candidate = readRun(runId);
            if (candidate == null) {
                return;
            }
            FilePersistenceSupport.withFileLock(rootDirectory, runPath(candidate.id(), candidate.tenantId()), () -> {
                StoredRun stored = readRun(runId);
                if (stored == null) {
                    return null;
                }
                Map<String, Object> variables = new HashMap<>(stored.variables());
                variables.put(key, value);
                writeRun(stored.withVariables(variables));
                return null;
            });
        });
    }

    @Override
    public Uni<Void> updateNodeExecution(WorkflowRunId runId, NodeId nodeId, NodeExecutionSnapshot snapshot) {
        return Uni.createFrom().voidItem().invoke(() -> {
            StoredRun candidate = readRun(runId);
            if (candidate == null) {
                return;
            }
            FilePersistenceSupport.withFileLock(rootDirectory, runPath(candidate.id(), candidate.tenantId()), () -> {
                StoredRun stored = readRun(runId);
                if (stored == null) {
                    return null;
                }
                Map<String, NodeExecutionSnapshot> nodeExecutions = new HashMap<>(stored.nodeExecutions());
                nodeExecutions.put(nodeId.value(), snapshot);
                writeRun(stored.withNodeExecutions(nodeExecutions));
                return null;
            });
        });
    }

    private StoredRun toStoredRun(WorkflowRun run) {
        WorkflowRunSnapshot snapshot = run.createSnapshot();
        return new StoredRun(
                snapshot.id(),
                snapshot.tenantId(),
                snapshot.definitionId(),
                snapshot.status(),
                snapshot.variables() != null ? new HashMap<>(snapshot.variables()) : Map.of(),
                toNodeSnapshots(snapshot.nodeExecutions()),
                snapshot.executionPath() != null ? List.copyOf(snapshot.executionPath()) : List.of(),
                snapshot.suspensionInfo(),
                snapshot.pendingSignals() != null ? new HashMap<>(snapshot.pendingSignals()) : Map.of(),
                snapshot.compensationState(),
                snapshot.createdAt(),
                snapshot.startedAt(),
                snapshot.completedAt(),
                snapshot.version(),
                Instant.now());
    }

    private WorkflowRun toDomain(StoredRun stored) {
        if (stored == null) {
            return null;
        }
        WorkflowDefinition definition = definitionRepository()
                .findByIdIncludingInactive(stored.definitionId(), stored.tenantId())
                .await()
                .indefinitely();
        if (definition == null) {
            throw new NoSuchElementException("WorkflowDefinition not found: " + stored.definitionId().value());
        }

        WorkflowRunSnapshot snapshot = new WorkflowRunSnapshot(
                stored.id(),
                stored.tenantId(),
                stored.definitionId(),
                stored.status() != null ? stored.status() : RunStatus.CREATED,
                stored.variables() != null ? stored.variables() : Map.of(),
                toNodeExecutions(stored.nodeExecutions(), definition),
                stored.executionPath() != null ? stored.executionPath() : List.of(),
                stored.suspensionInfo(),
                stored.pendingSignals() != null ? stored.pendingSignals() : Map.of(),
                stored.compensationState(),
                stored.createdAt() != null ? stored.createdAt() : Instant.now(),
                stored.startedAt(),
                stored.completedAt(),
                stored.version());
        return WorkflowRun.restore(snapshot, definition);
    }

    private Map<String, NodeExecutionSnapshot> toNodeSnapshots(Map<NodeId, NodeExecution> nodeExecutions) {
        Map<String, NodeExecutionSnapshot> snapshots = new HashMap<>();
        if (nodeExecutions == null) {
            return snapshots;
        }
        nodeExecutions.forEach((nodeId, execution) -> snapshots.put(nodeId.value(), new NodeExecutionSnapshot(
                nodeId.value(),
                execution.getStatus().name(),
                execution.getAttempt(),
                execution.getStartedAt(),
                execution.getCompletedAt(),
                execution.getRetryAt(),
                execution.getOutput(),
                toErrorSnapshot(execution.getLastError()))));
        return snapshots;
    }

    private Map<NodeId, NodeExecution> toNodeExecutions(
            Map<String, NodeExecutionSnapshot> snapshots,
            WorkflowDefinition definition) {
        Map<NodeId, NodeExecution> executions = new HashMap<>();
        if (snapshots == null) {
            return executions;
        }
        snapshots.forEach((key, snapshot) -> {
            NodeId nodeId = NodeId.of(snapshot.nodeId() != null ? snapshot.nodeId() : key);
            NodeDefinition nodeDefinition = definition.findNode(nodeId)
                    .orElseThrow(() -> new GamelanException(
                            ErrorCode.TASK_NOT_FOUND,
                            "Node not found in workflow definition: " + nodeId.value()));
            NodeExecution execution = NodeExecution.create(nodeId, nodeDefinition);
            execution.setStatus(toStatus(snapshot.status()));
            execution.setAttempt(snapshot.attempt() > 0 ? snapshot.attempt() : 1);
            execution.setStartedAt(snapshot.startedAt());
            execution.setCompletedAt(snapshot.completedAt());
            execution.setRetryAt(snapshot.retryAt());
            execution.setOutput(snapshot.output());
            execution.setLastError(toErrorInfo(snapshot.error()));
            executions.put(nodeId, execution);
        });
        return executions;
    }

    private NodeExecutionStatus toStatus(String status) {
        if (status == null || status.isBlank()) {
            return NodeExecutionStatus.PENDING;
        }
        try {
            return NodeExecutionStatus.valueOf(status);
        } catch (IllegalArgumentException error) {
            throw new GamelanException(
                    ErrorCode.STORAGE_SERIALIZATION_FAILED,
                    "Unknown node execution status: " + status,
                    error);
        }
    }

    private StoredRun readRun(WorkflowRunId id, TenantId tenantId) {
        return FilePersistenceSupport.read(runPath(id, tenantId), StoredRun.class, mapper());
    }

    private StoredRun readRun(WorkflowRunId id) {
        String runFileName = FilePersistenceSupport.fileName(id.value());
        List<StoredRun> matches = tenantDirectories().stream()
                .map(tenantDirectory -> tenantDirectory.resolve("runs").resolve(runFileName))
                .map(path -> FilePersistenceSupport.read(path, StoredRun.class, mapper()))
                .filter(Objects::nonNull)
                .filter(stored -> id.equals(stored.id()))
                .toList();
        if (matches.isEmpty()) {
            return null;
        }
        if (matches.size() > 1) {
            throw ambiguousRunId(id, matches);
        }
        return matches.get(0);
    }

    private GamelanException ambiguousRunId(WorkflowRunId id, List<StoredRun> matches) {
        String tenants = matches.stream()
                .map(StoredRun::tenantId)
                .map(TenantId::value)
                .distinct()
                .sorted()
                .toList()
                .toString();
        return new GamelanException(
                ErrorCode.CONCURRENCY_CONFLICT,
                "WorkflowRunId is not unique across tenants: " + id.value()
                        + " tenants=" + tenants + ". Use a tenant-aware repository API.");
    }

    private List<StoredRun> listRuns(TenantId tenantId) {
        return FilePersistenceSupport.listJsonFiles(runsDirectory(tenantId)).stream()
                .map(path -> FilePersistenceSupport.read(path, StoredRun.class, mapper()))
                .filter(Objects::nonNull)
                .toList();
    }

    private List<StoredRun> listAllRuns() {
        return tenantDirectories().stream()
                .flatMap(tenantDirectory -> FilePersistenceSupport.listJsonFiles(tenantDirectory.resolve("runs")).stream())
                .map(path -> FilePersistenceSupport.read(path, StoredRun.class, mapper()))
                .filter(Objects::nonNull)
                .toList();
    }

    private List<StoredRun> activeRecoveryRuns() {
        return listAllRuns().stream()
                .filter(stored -> stored.status() != null && stored.status().isActive())
                .sorted(java.util.Comparator.comparing(stored -> stored.id().value()))
                .toList();
    }

    private List<Path> tenantDirectories() {
        Path tenantsRoot = rootDirectory.resolve("tenants");
        if (!Files.isDirectory(tenantsRoot)) {
            return List.of();
        }
        try (Stream<Path> paths = Files.list(tenantsRoot)) {
            return paths
                    .filter(Files::isDirectory)
                    .toList();
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    private void writeRun(StoredRun stored) {
        FilePersistenceSupport.writeAtomic(rootDirectory, runPath(stored.id(), stored.tenantId()), stored, mapper());
    }

    private Path runPath(WorkflowRunId id, TenantId tenantId) {
        return runsDirectory(tenantId).resolve(FilePersistenceSupport.fileName(id.value()));
    }

    private Path runsDirectory(TenantId tenantId) {
        return rootDirectory
                .resolve("tenants")
                .resolve(FilePersistenceSupport.directoryName(tenantId.value()))
                .resolve("runs")
                .normalize();
    }

    private Path executionTokenPath(String tokenHash) {
        return executionTokensDirectory().resolve(tokenHash + ".json").normalize();
    }

    private Path callbackPath(String tokenHash) {
        return callbacksDirectory().resolve(tokenHash + ".json").normalize();
    }

    private Path executionTokensDirectory() {
        return rootDirectory.resolve("tokens").resolve("execution").normalize();
    }

    private Path callbacksDirectory() {
        return rootDirectory.resolve("tokens").resolve("callbacks").normalize();
    }

    private void pruneExpiredExecutionTokens() {
        pruneExpiredFiles(executionTokensDirectory(), StoredExecutionToken.class, StoredExecutionToken::isExpired);
    }

    private void pruneExpiredCallbacks() {
        pruneExpiredFiles(callbacksDirectory(), StoredCallbackRegistration.class, StoredCallbackRegistration::isExpired);
    }

    private <T> void pruneExpiredFiles(Path directory, Class<T> type, Predicate<T> expired) {
        FilePersistenceSupport.listJsonFiles(directory).forEach(path -> {
            T stored = FilePersistenceSupport.read(path, type, mapper());
            if (stored != null && expired.test(stored)) {
                FilePersistenceSupport.deleteIfExists(rootDirectory, path);
            }
        });
    }

    private ErrorSnapshot toErrorSnapshot(ErrorInfo error) {
        if (error == null) {
            return null;
        }
        return new ErrorSnapshot(error.code(), error.message(), error.stackTrace());
    }

    private ErrorInfo toErrorInfo(ErrorSnapshot error) {
        if (error == null) {
            return null;
        }
        return new ErrorInfo(error.code(), error.message(), error.stackTrace(), Map.of());
    }

    private WorkflowDefinitionRepository definitionRepository() {
        if (definitionRepository == null) {
            throw new IllegalStateException("WorkflowDefinitionRepository is not configured");
        }
        return definitionRepository;
    }

    private <T> Uni<T> withTenantLock(
            WorkflowRunId runId,
            TenantId tenantId,
            Function<WorkflowRun, Uni<T>> action,
            boolean rejectAmbiguousRunId) {
        return FilePersistenceSupport.withFileLock(rootDirectory, runPath(runId, tenantId), () -> {
            try {
                StoredRun stored = rejectAmbiguousRunId ? readRun(runId) : readRun(runId, tenantId);
                if (stored == null) {
                    return Uni.createFrom().failure(new NoSuchElementException(
                            "WorkflowRun not found: " + runId.value()));
                }
                WorkflowRun run = toDomain(stored);
                T result = action.apply(run).await().indefinitely();
                return uniItem(result);
            } catch (Throwable error) {
                return Uni.createFrom().failure(error);
            }
        });
    }

    private ObjectMapper mapper() {
        return objectMapper != null ? objectMapper : fallbackObjectMapper;
    }

    @SuppressWarnings("unchecked")
    private <T> Uni<T> uniItem(T value) {
        return value != null ? Uni.createFrom().item(value) : (Uni<T>) Uni.createFrom().nullItem();
    }

    private record StoredRun(
            WorkflowRunId id,
            TenantId tenantId,
            WorkflowDefinitionId definitionId,
            RunStatus status,
            Map<String, Object> variables,
            Map<String, NodeExecutionSnapshot> nodeExecutions,
            List<String> executionPath,
            SuspensionInfo suspensionInfo,
            Map<String, Signal> pendingSignals,
            CompensationState compensationState,
            Instant createdAt,
            Instant startedAt,
            Instant completedAt,
            long version,
            Instant updatedAt) {

        StoredRun {
            variables = variables != null ? Collections.unmodifiableMap(new HashMap<>(variables)) : Map.of();
            nodeExecutions = nodeExecutions != null ? Collections.unmodifiableMap(new HashMap<>(nodeExecutions)) : Map.of();
            executionPath = executionPath != null ? List.copyOf(executionPath) : List.of();
            pendingSignals = pendingSignals != null ? Collections.unmodifiableMap(new HashMap<>(pendingSignals)) : Map.of();
            updatedAt = updatedAt != null ? updatedAt : Instant.now();
        }

        StoredRun withVariables(Map<String, Object> variables) {
            return new StoredRun(id, tenantId, definitionId, status, variables, nodeExecutions, executionPath,
                    suspensionInfo, pendingSignals, compensationState,
                    createdAt, startedAt, completedAt, version, Instant.now());
        }

        StoredRun withNodeExecutions(Map<String, NodeExecutionSnapshot> nodeExecutions) {
            return new StoredRun(id, tenantId, definitionId, status, variables, nodeExecutions, executionPath,
                    suspensionInfo, pendingSignals, compensationState,
                    createdAt, startedAt, completedAt, version, Instant.now());
        }
    }

    private record StoredExecutionToken(
            String tokenHash,
            WorkflowRunId runId,
            TenantId tenantId,
            NodeId nodeId,
            int attempt,
            Instant expiresAt,
            Instant createdAt) {

        static StoredExecutionToken from(ExecutionToken token) {
            return new StoredExecutionToken(
                    ExecutionTokenHash.sha256(token.value()),
                    token.runId(),
                    token.tenantId(),
                    token.nodeId(),
                    token.attempt(),
                    token.expiresAt(),
                    Instant.now());
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
            String tokenHash,
            WorkflowRunId runId,
            TenantId tenantId,
            NodeId nodeId,
            Instant expiresAt,
            Instant createdAt) {

        static StoredCallbackRegistration from(CallbackRegistration callback) {
            return new StoredCallbackRegistration(
                    BearerTokenHash.sha256(callback.callbackToken()),
                    callback.runId(),
                    callback.tenantId(),
                    callback.nodeId(),
                    callback.expiresAt(),
                    Instant.now());
        }

        boolean isExpired() {
            return Instant.now().isAfter(expiresAt);
        }

        boolean tenantMatches(TenantId expectedTenantId) {
            return tenantId == null || tenantId.equals(expectedTenantId);
        }
    }
}
