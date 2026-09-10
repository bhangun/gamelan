package tech.kayys.gamelan.runtime.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.smallrye.mutiny.Uni;
import tech.kayys.gamelan.engine.callback.CallbackRegistration;
import tech.kayys.gamelan.engine.error.ErrorCode;
import tech.kayys.gamelan.engine.error.GamelanException;
import tech.kayys.gamelan.engine.event.ExecutionEvent;
import tech.kayys.gamelan.engine.event.GenericExecutionEvent;
import tech.kayys.gamelan.engine.execution.BearerTokenHash;
import tech.kayys.gamelan.engine.execution.ExecutionHistory;
import tech.kayys.gamelan.engine.execution.ExecutionHistoryRepository;
import tech.kayys.gamelan.engine.execution.ExecutionToken;
import tech.kayys.gamelan.engine.execution.ExecutionTokenHash;
import tech.kayys.gamelan.engine.execution.contract.ExecutionHistoryRepositoryCompensationMarkerContract;
import tech.kayys.gamelan.engine.execution.contract.ExecutionHistoryRepositoryHistoryContract;
import tech.kayys.gamelan.engine.execution.contract.ExecutionHistoryRepositoryIdempotencyMarkerContract;
import tech.kayys.gamelan.engine.node.NodeDefinition;
import tech.kayys.gamelan.engine.node.NodeExecutionStatus;
import tech.kayys.gamelan.engine.node.NodeId;
import tech.kayys.gamelan.engine.node.NodeType;
import tech.kayys.gamelan.engine.repository.WorkflowDefinitionRepository;
import tech.kayys.gamelan.engine.repository.contract.WorkflowDefinitionRepositoryContract;
import tech.kayys.gamelan.engine.repository.WorkflowRunRepository;
import tech.kayys.gamelan.engine.repository.contract.WorkflowRunRepositoryContract;
import tech.kayys.gamelan.engine.run.RetryPolicy;
import tech.kayys.gamelan.engine.run.RunStatus;
import tech.kayys.gamelan.engine.saga.CompensationPolicy;
import tech.kayys.gamelan.engine.signal.Signal;
import tech.kayys.gamelan.engine.tenant.TenantId;
import tech.kayys.gamelan.engine.workflow.WorkflowDefinition;
import tech.kayys.gamelan.engine.workflow.WorkflowDefinitionId;
import tech.kayys.gamelan.engine.workflow.WorkflowMode;
import tech.kayys.gamelan.engine.workflow.WorkflowRun;
import tech.kayys.gamelan.engine.workflow.WorkflowRunId;
import tech.kayys.gamelan.engine.workflow.WorkflowRunSnapshot;

class FileWorkflowPersistenceTest implements ExecutionHistoryRepositoryCompensationMarkerContract,
        ExecutionHistoryRepositoryHistoryContract,
        ExecutionHistoryRepositoryIdempotencyMarkerContract,
        WorkflowDefinitionRepositoryContract,
        WorkflowRunRepositoryContract {

    private static final TenantId TENANT = TenantId.of("tenant-1");
    private static final NodeId NODE_ID = NodeId.of("node-1");

    @TempDir
    java.nio.file.Path tempDir;

    @Override
    public ExecutionHistoryRepository newExecutionHistoryRepository() {
        return new FileExecutionHistoryRepository(tempDir);
    }

    @Override
    public WorkflowDefinitionRepository newWorkflowDefinitionRepository() {
        return new FileWorkflowDefinitionRepository(tempDir);
    }

    @Override
    public WorkflowRunRepository newWorkflowRunRepository(WorkflowDefinition definition) {
        FileWorkflowDefinitionRepository definitions = new FileWorkflowDefinitionRepository(tempDir);
        definitions.save(definition, definition.tenantId()).await().indefinitely();
        return new FileWorkflowRunRepository(tempDir, definitions);
    }

    @Test
    void definitionRepository_persistsActiveStateAcrossInstances() {
        WorkflowDefinition definition = definition();
        FileWorkflowDefinitionRepository writer = new FileWorkflowDefinitionRepository(tempDir);

        writer.save(definition, TENANT).await().indefinitely();
        writer.delete(definition.id(), TENANT).await().indefinitely();

        FileWorkflowDefinitionRepository reader = new FileWorkflowDefinitionRepository(tempDir);
        assertNull(reader.findById(definition.id(), TENANT).await().indefinitely());
        assertEquals(definition, reader.findByIdIncludingInactive(definition.id(), TENANT).await().indefinitely());
        assertEquals(List.of(definition), reader.findByTenant(TENANT, false).await().indefinitely());
        assertEquals(List.of(), reader.findByTenant(TENANT, true).await().indefinitely());
    }

    @Test
    void runRepository_persistsRunsTokensAndCallbacksAcrossInstances() {
        WorkflowDefinition definition = definition();
        FileWorkflowDefinitionRepository definitions = new FileWorkflowDefinitionRepository(tempDir);
        definitions.save(definition, TENANT).await().indefinitely();

        WorkflowRun run = WorkflowRun.create(TENANT, definition, Map.of("input", "value"));
        run.start();
        run.startNode(NODE_ID, 1);
        run.completeNode(NODE_ID, 1, Map.of("result", "ok"));

        FileWorkflowRunRepository writer = new FileWorkflowRunRepository(tempDir, definitions);
        writer.persist(run).await().indefinitely();
        ExecutionToken token = new ExecutionToken("execution-secret", run.getId(), TENANT, NODE_ID, 1,
                Instant.now().plusSeconds(60));
        writer.storeToken(token).await().indefinitely();
        writer.storeCallback(new CallbackRegistration("callback-secret", run.getId(), NODE_ID,
                "http://localhost/callback", Instant.now().plusSeconds(60))).await().indefinitely();
        writer.storeCallback(new CallbackRegistration(
                "tenant-callback-secret",
                run.getId(),
                TENANT,
                NODE_ID,
                "http://localhost/callback",
                Instant.now().plusSeconds(60))).await().indefinitely();

        FileWorkflowDefinitionRepository readerDefinitions = new FileWorkflowDefinitionRepository(tempDir);
        FileWorkflowRunRepository reader = new FileWorkflowRunRepository(tempDir, readerDefinitions);
        WorkflowRun restored = reader.findById(run.getId(), TENANT).await().indefinitely();

        assertEquals(RunStatus.COMPLETED, restored.getStatus());
        assertEquals(NodeExecutionStatus.COMPLETED, restored.getNodeExecution(NODE_ID).getStatus());
        assertEquals("ok", restored.getNodeExecution(NODE_ID).getOutput().get("result"));
        List<WorkflowRun> completedRuns = reader.query(TENANT, definition.id(), RunStatus.COMPLETED, 0, 10)
                .await().indefinitely();
        assertEquals(1, completedRuns.size());
        assertEquals(run.getId(), completedRuns.getFirst().getId());
        assertTrue(reader.validateToken(token).await().indefinitely());
        assertFalse(reader.validateToken(new ExecutionToken(
                token.value(),
                run.getId(),
                TenantId.of("tenant-2"),
                NODE_ID,
                1,
                token.expiresAt())).await().indefinitely());
        assertTrue(reader.validateCallback(run.getId(), "callback-secret").await().indefinitely());
        assertFalse(reader.validateCallback(WorkflowRunId.of("other-run"), "callback-secret").await().indefinitely());
        assertTrue(reader.validateCallback(run.getId(), TENANT, "tenant-callback-secret").await().indefinitely());
        assertFalse(reader.validateCallback(run.getId(), TenantId.of("tenant-2"), "tenant-callback-secret")
                .await().indefinitely());
        assertTrue(reader.validateCallback(run.getId(), "tenant-callback-secret").await().indefinitely());
    }

    @Test
    void runRepository_preservesSuspensionAcrossInstances() {
        WorkflowDefinition definition = definition();
        FileWorkflowDefinitionRepository definitions = new FileWorkflowDefinitionRepository(tempDir);
        definitions.save(definition, TENANT).await().indefinitely();

        WorkflowRun run = WorkflowRun.create(TENANT, definition, Map.of());
        run.start();
        run.startNode(NODE_ID, 1);
        run.suspend("waiting for callback", NODE_ID);

        FileWorkflowRunRepository writer = new FileWorkflowRunRepository(tempDir, definitions);
        writer.persist(run).await().indefinitely();

        FileWorkflowRunRepository reader = new FileWorkflowRunRepository(tempDir, new FileWorkflowDefinitionRepository(tempDir));
        WorkflowRun restored = reader.findById(run.getId(), TENANT).await().indefinitely();
        restored.signal(new Signal("approved", NODE_ID, Map.of("approved", true), Instant.now()));

        assertEquals(RunStatus.RUNNING, restored.getStatus());
        assertEquals(true, restored.getContext().getVariable("approved"));
    }

    @Test
    void runRepository_preservesPendingSignalsAcrossInstances() {
        WorkflowDefinition definition = definition();
        FileWorkflowDefinitionRepository definitions = new FileWorkflowDefinitionRepository(tempDir);
        definitions.save(definition, TENANT).await().indefinitely();

        WorkflowRun run = WorkflowRun.create(TENANT, definition, Map.of());
        run.start();
        run.startNode(NODE_ID, 1);
        run.signal(new Signal("approved", NODE_ID, Map.of("approved", true), Instant.now()));

        FileWorkflowRunRepository writer = new FileWorkflowRunRepository(tempDir, definitions);
        writer.persist(run).await().indefinitely();

        FileWorkflowRunRepository reader = new FileWorkflowRunRepository(tempDir, new FileWorkflowDefinitionRepository(tempDir));
        WorkflowRun restored = reader.findById(run.getId(), TENANT).await().indefinitely();
        restored.suspend("waiting for callback", NODE_ID);

        assertEquals(RunStatus.RUNNING, restored.getStatus());
        assertEquals(true, restored.getContext().getVariable("approved"));
    }

    @Test
    void runRepository_prunesExpiredTokenAndCallbackFiles() {
        WorkflowDefinition definition = definition();
        FileWorkflowDefinitionRepository definitions = new FileWorkflowDefinitionRepository(tempDir);
        definitions.save(definition, TENANT).await().indefinitely();

        WorkflowRun run = WorkflowRun.create(TENANT, definition, Map.of());
        FileWorkflowRunRepository repository = new FileWorkflowRunRepository(tempDir, definitions);
        repository.persist(run).await().indefinitely();

        ExecutionToken expiredToken = new ExecutionToken("expired-execution-token", run.getId(), NODE_ID, 1,
                Instant.now().minusSeconds(1));
        Path expiredTokenPath = executionTokenPath(expiredToken.value());
        repository.storeToken(expiredToken).await().indefinitely();
        assertTrue(Files.isRegularFile(expiredTokenPath));
        assertFalse(repository.validateToken(expiredToken).await().indefinitely());
        assertFalse(Files.exists(expiredTokenPath));

        ExecutionToken staleToken = new ExecutionToken("stale-execution-token", run.getId(), NODE_ID, 1,
                Instant.now().minusSeconds(1));
        ExecutionToken freshToken = new ExecutionToken("fresh-execution-token", run.getId(), NODE_ID, 1,
                Instant.now().plusSeconds(60));
        Path staleTokenPath = executionTokenPath(staleToken.value());
        Path freshTokenPath = executionTokenPath(freshToken.value());
        repository.storeToken(staleToken).await().indefinitely();
        repository.storeToken(freshToken).await().indefinitely();
        assertFalse(Files.exists(staleTokenPath));
        assertTrue(Files.isRegularFile(freshTokenPath));
        assertTrue(repository.validateToken(freshToken).await().indefinitely());

        CallbackRegistration expiredCallback = callback("expired-callback-token", run.getId(), -1);
        Path expiredCallbackPath = callbackPath(expiredCallback.callbackToken());
        repository.storeCallback(expiredCallback).await().indefinitely();
        assertTrue(Files.isRegularFile(expiredCallbackPath));
        assertFalse(repository.validateCallback(run.getId(), expiredCallback.callbackToken()).await().indefinitely());
        assertFalse(Files.exists(expiredCallbackPath));

        CallbackRegistration staleCallback = callback("stale-callback-token", run.getId(), -1);
        CallbackRegistration freshCallback = callback("fresh-callback-token", run.getId(), 60);
        Path staleCallbackPath = callbackPath(staleCallback.callbackToken());
        Path freshCallbackPath = callbackPath(freshCallback.callbackToken());
        repository.storeCallback(staleCallback).await().indefinitely();
        repository.storeCallback(freshCallback).await().indefinitely();
        assertFalse(Files.exists(staleCallbackPath));
        assertTrue(Files.isRegularFile(freshCallbackPath));
        assertTrue(repository.validateCallback(run.getId(), freshCallback.callbackToken()).await().indefinitely());

        CallbackRegistration tenantCallback = new CallbackRegistration(
                "tenant-callback-token",
                run.getId(),
                TENANT,
                NODE_ID,
                "http://localhost/callback",
                Instant.now().plusSeconds(60));
        repository.storeCallback(tenantCallback).await().indefinitely();
        assertTrue(repository.validateCallback(run.getId(), TENANT, tenantCallback.callbackToken()).await().indefinitely());
        assertFalse(repository.validateCallback(run.getId(), TenantId.of("tenant-2"), tenantCallback.callbackToken())
                .await().indefinitely());
    }

    @Test
    void repositories_hashTenantDirectoryNamesToAvoidPathTraversal() {
        String outsideName = "tenant-outside-" + UUID.randomUUID();
        TenantId pathLikeTenant = TenantId.of("../../" + outsideName + "/path");
        WorkflowDefinition definition = definition(pathLikeTenant);
        FileWorkflowDefinitionRepository definitions = new FileWorkflowDefinitionRepository(tempDir);
        FileWorkflowRunRepository runs = new FileWorkflowRunRepository(tempDir, definitions);

        definitions.save(definition, pathLikeTenant).await().indefinitely();
        WorkflowRun run = WorkflowRun.create(pathLikeTenant, definition, Map.of());
        runs.persist(run).await().indefinitely();

        Path tenantDirectory = tempDir.resolve("tenants")
                .resolve(FilePersistenceSupport.directoryName(pathLikeTenant.value()));
        assertTrue(Files.isDirectory(tenantDirectory));
        assertTrue(Files.isRegularFile(tenantDirectory.resolve("definitions")
                .resolve(FilePersistenceSupport.fileName(definition.id().value()))));
        assertTrue(Files.isRegularFile(tenantDirectory.resolve("runs")
                .resolve(FilePersistenceSupport.fileName(run.getId().value()))));
        assertEquals(definition, definitions.findById(definition.id(), pathLikeTenant).await().indefinitely());
        assertEquals(run.getId(), runs.findById(run.getId(), pathLikeTenant).await().indefinitely().getId());
        assertFalse(Files.exists(tempDir.getParent().resolve(outsideName)));
    }

    @Test
    void runRepository_serializesConcurrentSurgicalContextUpdatesAcrossInstances() throws Exception {
        WorkflowDefinition definition = definition();
        FileWorkflowDefinitionRepository definitions = new FileWorkflowDefinitionRepository(tempDir);
        definitions.save(definition, TENANT).await().indefinitely();

        WorkflowRun run = WorkflowRun.create(TENANT, definition, Map.of("initial", "value"));
        List<FileWorkflowRunRepository> repositories = IntStream.range(0, 4)
                .mapToObj(ignored -> new FileWorkflowRunRepository(tempDir, definitions))
                .toList();
        repositories.getFirst().persist(run).await().indefinitely();

        int concurrency = 16;
        ExecutorService executor = Executors.newFixedThreadPool(concurrency);
        CountDownLatch ready = new CountDownLatch(concurrency);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<CompletableFuture<Void>> updates = IntStream.range(0, 64)
                    .mapToObj(index -> CompletableFuture.runAsync(() -> {
                        ready.countDown();
                        awaitLatch(start);
                        repositories.get(index % repositories.size())
                                .updateContextVariable(run.getId(), "key-" + index, index)
                                .await()
                                .indefinitely();
                    }, executor))
                    .toList();

            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            updates.forEach(CompletableFuture::join);
        } finally {
            start.countDown();
            executor.shutdownNow();
        }

        WorkflowRun restored = repositories.getFirst().findById(run.getId(), TENANT).await().indefinitely();
        assertEquals("value", restored.getContext().getVariable("initial"));
        IntStream.range(0, 64).forEach(index ->
                assertEquals(index, restored.getContext().getVariable("key-" + index)));
    }

    @Test
    void runRepository_tenantAwareLockReadsTenantSpecificRunWhenRunIdsCollide() {
        TenantId tenantA = TenantId.of("tenant-a");
        TenantId tenantB = TenantId.of("tenant-b");
        WorkflowRunId sharedRunId = WorkflowRunId.of("shared-run-id");

        FileWorkflowDefinitionRepository definitions = new FileWorkflowDefinitionRepository(tempDir);
        definitions.save(definition(tenantA), tenantA).await().indefinitely();
        definitions.save(definition(tenantB), tenantB).await().indefinitely();

        FileWorkflowRunRepository repository = new FileWorkflowRunRepository(tempDir, definitions);
        repository.persist(restoredRun(tenantA, sharedRunId, Map.of("owner", "a"))).await().indefinitely();
        repository.persist(restoredRun(tenantB, sharedRunId, Map.of("owner", "b"))).await().indefinitely();

        WorkflowRun lockedRun = repository.withLock(sharedRunId, tenantB, run -> Uni.createFrom().item(run))
                .await()
                .indefinitely();

        assertEquals(tenantB, lockedRun.getTenantId());
        assertEquals("b", lockedRun.getContext().getVariable("owner"));
    }

    @Test
    void runRepository_tenantAwareLocksDoNotBlockDifferentTenantsWithSameRunId() throws Exception {
        TenantId tenantA = TenantId.of("tenant-a");
        TenantId tenantB = TenantId.of("tenant-b");
        WorkflowRunId sharedRunId = WorkflowRunId.of("shared-lock-run-id");

        FileWorkflowDefinitionRepository definitions = new FileWorkflowDefinitionRepository(tempDir);
        definitions.save(definition(tenantA), tenantA).await().indefinitely();
        definitions.save(definition(tenantB), tenantB).await().indefinitely();

        FileWorkflowRunRepository repository = new FileWorkflowRunRepository(tempDir, definitions);
        repository.persist(restoredRun(tenantA, sharedRunId, Map.of("owner", "a"))).await().indefinitely();
        repository.persist(restoredRun(tenantB, sharedRunId, Map.of("owner", "b"))).await().indefinitely();
        CountDownLatch tenantALockEntered = new CountDownLatch(1);
        CompletableFuture<Void> releaseTenantA = new CompletableFuture<>();

        CompletableFuture<WorkflowRun> tenantALock = CompletableFuture.supplyAsync(() -> repository
                .withLock(sharedRunId, tenantA, run -> {
                    tenantALockEntered.countDown();
                    return Uni.createFrom().completionStage(releaseTenantA.thenApply(ignored -> run));
                })
                .await()
                .indefinitely());
        assertTrue(tenantALockEntered.await(2, TimeUnit.SECONDS));

        CompletableFuture<WorkflowRun> tenantBLock = CompletableFuture.supplyAsync(() -> repository
                .withLock(sharedRunId, tenantB, run -> Uni.createFrom().item(run))
                .await()
                .indefinitely());

        WorkflowRun tenantBRun = tenantBLock.get(500, TimeUnit.MILLISECONDS);
        assertEquals(tenantB, tenantBRun.getTenantId());
        assertFalse(tenantALock.isDone());

        releaseTenantA.complete(null);
        assertEquals(tenantA, tenantALock.get(2, TimeUnit.SECONDS).getTenantId());
    }

    @Test
    void runRepository_genericRunOperationsRejectAmbiguousCrossTenantRunIds() {
        TenantId tenantA = TenantId.of("tenant-a");
        TenantId tenantB = TenantId.of("tenant-b");
        WorkflowRunId sharedRunId = WorkflowRunId.of("ambiguous-run-id");

        FileWorkflowDefinitionRepository definitions = new FileWorkflowDefinitionRepository(tempDir);
        definitions.save(definition(tenantA), tenantA).await().indefinitely();
        definitions.save(definition(tenantB), tenantB).await().indefinitely();

        FileWorkflowRunRepository repository = new FileWorkflowRunRepository(tempDir, definitions);
        repository.persist(restoredRun(tenantA, sharedRunId, Map.of("owner", "a"))).await().indefinitely();
        repository.persist(restoredRun(tenantB, sharedRunId, Map.of("owner", "b"))).await().indefinitely();

        GamelanException lookupError = assertThrows(GamelanException.class,
                () -> repository.findById(sharedRunId).await().indefinitely());
        GamelanException updateError = assertThrows(GamelanException.class,
                () -> repository.updateContextVariable(sharedRunId, "unsafe", true).await().indefinitely());

        assertEquals(ErrorCode.CONCURRENCY_CONFLICT, lookupError.getErrorCode());
        assertEquals(ErrorCode.CONCURRENCY_CONFLICT, updateError.getErrorCode());
        assertEquals("a", repository.findById(sharedRunId, tenantA)
                .await()
                .indefinitely()
                .getContext()
                .getVariable("owner"));
        assertEquals("b", repository.findById(sharedRunId, tenantB)
                .await()
                .indefinitely()
                .getContext()
                .getVariable("owner"));
    }

    @Test
    void runRepository_genericLookupIgnoresDefinitionFilesWithCollidingIds() {
        WorkflowRunId collidingId = WorkflowRunId.of("definition-and-run-share-this-id");
        WorkflowDefinition definition = definition(TENANT, WorkflowDefinitionId.of(collidingId.value()));
        FileWorkflowDefinitionRepository definitions = new FileWorkflowDefinitionRepository(tempDir);
        definitions.save(definition, TENANT).await().indefinitely();

        FileWorkflowRunRepository repository = new FileWorkflowRunRepository(tempDir, definitions);
        repository.persist(restoredRun(TENANT, collidingId, definition, Map.of("kind", "run"))).await().indefinitely();

        WorkflowRun restored = repository.findById(collidingId).await().indefinitely();

        assertEquals(collidingId, restored.getId());
        assertEquals("run", restored.getContext().getVariable("kind"));
    }

    @Test
    void executionHistoryRepository_persistsEventsAndProcessedNodeMarkers() {
        FileExecutionHistoryRepository writer = new FileExecutionHistoryRepository(tempDir);
        WorkflowRunId runId = WorkflowRunId.of("run-1");

        writer.append(runId, "RUNNING", "Run started", Map.of("source", "test")).await().indefinitely();
        writer.appendEvents(runId, List.of(new GenericExecutionEvent(runId, "NODE_COMPLETED",
                "Node completed", Instant.now(), Map.of()))).await().indefinitely();
        assertFalse(writer.isNodeResultProcessed(runId, NODE_ID, 1).await().indefinitely());
        assertTrue(writer.markNodeResultProcessed(runId, NODE_ID, 1).await().indefinitely());
        assertFalse(writer.isExternalSignalProcessed(runId, "callback-hash").await().indefinitely());
        assertTrue(writer.markExternalSignalProcessed(runId, "callback-hash").await().indefinitely());
        assertFalse(writer.isCompensationNodeProcessed(runId, NODE_ID).await().indefinitely());
        assertTrue(writer.markCompensationNodeProcessed(runId, NODE_ID).await().indefinitely());
        assertTrue(Files.isRegularFile(historyLogPath(runId)));

        FileExecutionHistoryRepository reader = new FileExecutionHistoryRepository(tempDir);
        assertEquals(2, reader.load(runId).await().indefinitely().getEvents().size());
        assertTrue(reader.isNodeResultProcessed(runId, NODE_ID, 1).await().indefinitely());
        assertTrue(reader.isExternalSignalProcessed(runId, "callback-hash").await().indefinitely());
        assertTrue(reader.isCompensationNodeProcessed(runId, NODE_ID).await().indefinitely());
    }

    @Test
    void executionHistoryRepository_preservesGenericEventMessageAndMetadataAcrossInstances() {
        FileExecutionHistoryRepository writer = new FileExecutionHistoryRepository(tempDir);
        WorkflowRunId runId = WorkflowRunId.of("run-history-generic-payload");

        writer.appendEvents(runId, TENANT, List.of(new GenericExecutionEvent(
                "generic-event-1",
                runId,
                "AgentReasoning",
                "agent selected browser tool",
                Instant.EPOCH,
                Map.of("agentId", "agent-1", "tool", "browser"))))
                .await()
                .indefinitely();

        FileExecutionHistoryRepository reader = new FileExecutionHistoryRepository(tempDir);
        ExecutionHistory.ExecutionEventHistory event = reader.load(runId, TENANT)
                .await()
                .indefinitely()
                .getEvents()
                .getFirst();

        assertEquals("agent selected browser tool", event.getPayload().get("message"));
        assertEquals("AgentReasoning", event.getMetadata().get("domainEventType"));
        assertEquals("agent-1", event.getMetadata().get("agentId"));
        assertEquals("browser", event.getMetadata().get("tool"));
    }

    @Test
    void executionHistoryRepository_readsLegacySnapshotHistoryAndAppendedLog() throws Exception {
        FileExecutionHistoryRepository repository = new FileExecutionHistoryRepository(tempDir);
        WorkflowRunId runId = WorkflowRunId.of("run-history-legacy");
        Path legacySnapshotPath = historySnapshotPath(runId);
        Files.createDirectories(legacySnapshotPath.getParent());
        Files.writeString(
                legacySnapshotPath,
                """
                        {
                          "events": [
                            {
                              "eventId": "legacy-event",
                              "type": "NodeCompletedEvent",
                              "message": "legacy event",
                              "occurredAt": "2026-05-24T00:00:00Z",
                              "metadata": { "source": "legacy-json" }
                            }
                          ],
                          "updatedAt": "2026-05-24T00:00:00Z"
                        }
                        """,
                StandardCharsets.UTF_8);

        repository.append(runId, "NodeFailedEvent", "new event", Map.of("source", "jsonl"))
                .await()
                .indefinitely();

        ExecutionHistory history = repository.load(runId).await().indefinitely();

        assertEquals(2, history.getEvents().size());
        assertEquals(ExecutionHistory.ExecutionEventHistory.ExecutionEventType.NODE_COMPLETED,
                history.getEvents().get(0).getEventType());
        assertEquals(ExecutionHistory.ExecutionEventHistory.ExecutionEventType.NODE_FAILED,
                history.getEvents().get(1).getEventType());
    }

    @Test
    void executionHistoryRepository_isolatesTenantAwareHistoryAndProcessedNodeMarkers() {
        FileExecutionHistoryRepository repository = new FileExecutionHistoryRepository(tempDir);
        WorkflowRunId sharedRunId = WorkflowRunId.of("shared-history-run-id");
        TenantId tenantA = TenantId.of("tenant-a");
        TenantId tenantB = TenantId.of("tenant-b");

        repository.append(sharedRunId, tenantA, "NodeCompletedEvent", "tenant-a event", Map.of())
                .await()
                .indefinitely();
        repository.append(sharedRunId, tenantB, "NodeFailedEvent", "tenant-b event", Map.of())
                .await()
                .indefinitely();
        assertTrue(Files.isRegularFile(historyLogPath(sharedRunId, tenantA)));
        assertTrue(Files.isRegularFile(historyLogPath(sharedRunId, tenantB)));

        assertEquals(ExecutionHistory.ExecutionEventHistory.ExecutionEventType.NODE_COMPLETED, repository.load(sharedRunId,
                tenantA)
                .await()
                .indefinitely()
                .getEvents()
                .getFirst()
                .getEventType());
        assertEquals(ExecutionHistory.ExecutionEventHistory.ExecutionEventType.NODE_FAILED, repository.load(sharedRunId,
                tenantB)
                .await()
                .indefinitely()
                .getEvents()
                .getFirst()
                .getEventType());
        assertTrue(repository.markNodeResultProcessed(sharedRunId, tenantA, NODE_ID, 1).await().indefinitely());
        assertFalse(repository.isNodeResultProcessed(sharedRunId, tenantB, NODE_ID, 1).await().indefinitely());
        assertTrue(repository.markNodeResultProcessed(sharedRunId, tenantB, NODE_ID, 1).await().indefinitely());
        assertFalse(repository.markNodeResultProcessed(sharedRunId, tenantA, NODE_ID, 1).await().indefinitely());
        assertTrue(repository.markExternalSignalProcessed(sharedRunId, tenantA, "callback-hash").await().indefinitely());
        assertFalse(repository.isExternalSignalProcessed(sharedRunId, tenantB, "callback-hash").await().indefinitely());
        assertTrue(repository.markExternalSignalProcessed(sharedRunId, tenantB, "callback-hash").await().indefinitely());
        assertFalse(repository.markExternalSignalProcessed(sharedRunId, tenantA, "callback-hash").await().indefinitely());
        assertTrue(repository.markCompensationNodeProcessed(sharedRunId, tenantA, NODE_ID).await().indefinitely());
        assertFalse(repository.isCompensationNodeProcessed(sharedRunId, tenantB, NODE_ID).await().indefinitely());
        assertTrue(repository.markCompensationNodeProcessed(sharedRunId, tenantB, NODE_ID).await().indefinitely());
        assertFalse(repository.markCompensationNodeProcessed(sharedRunId, tenantA, NODE_ID).await().indefinitely());

        NodeId legacyCompensationNode = NodeId.of("legacy-compensation-node");
        assertTrue(repository.markCompensationNodeProcessed(sharedRunId, legacyCompensationNode).await().indefinitely());
        assertTrue(repository.isCompensationNodeProcessed(sharedRunId, tenantA, legacyCompensationNode)
                .await()
                .indefinitely());
        assertFalse(repository.markCompensationNodeProcessed(sharedRunId, tenantA, legacyCompensationNode)
                .await()
                .indefinitely());
    }

    @Test
    void executionHistoryRepository_claimsProcessedNodeMarkerAtomicallyUnderConcurrency() {
        FileExecutionHistoryRepository repository = new FileExecutionHistoryRepository(tempDir);
        WorkflowRunId runId = WorkflowRunId.of("run-concurrent");

        List<CompletableFuture<Boolean>> claims = IntStream.range(0, 32)
                .mapToObj(ignored -> CompletableFuture.supplyAsync(() ->
                        repository.markNodeResultProcessed(runId, NODE_ID, 1).await().indefinitely()))
                .toList();

        long firstClaimCount = claims.stream()
                .map(CompletableFuture::join)
                .filter(Boolean.TRUE::equals)
                .count();

        assertEquals(1, firstClaimCount);
        assertTrue(repository.isNodeResultProcessed(runId, NODE_ID, 1).await().indefinitely());
    }

    @Test
    void executionHistoryRepository_ignoresPartialTrailingHistoryLogRecord() throws Exception {
        FileExecutionHistoryRepository repository = new FileExecutionHistoryRepository(tempDir);
        WorkflowRunId runId = WorkflowRunId.of("run-history-partial");

        repository.append(runId, TENANT, "NodeCompletedEvent", "first event", Map.of())
                .await()
                .indefinitely();
        Files.writeString(
                historyLogPath(runId, TENANT),
                "{\"eventId\":\"partial\"",
                StandardCharsets.UTF_8,
                StandardOpenOption.APPEND);

        ExecutionHistory history = repository.load(runId, TENANT).await().indefinitely();

        assertEquals(1, history.getEvents().size());
    }

    @Test
    void executionHistoryRepository_loadsLargeAppendOnlyHistoryLog() {
        FileExecutionHistoryRepository repository = new FileExecutionHistoryRepository(tempDir);
        WorkflowRunId runId = WorkflowRunId.of("run-history-large-log");
        List<ExecutionEvent> events = IntStream.range(0, 1_000)
                .mapToObj(index -> new GenericExecutionEvent(
                        "event-" + index,
                        runId,
                        "NodeCompletedEvent",
                        "event " + index,
                        Instant.now(),
                        Map.of("index", index)))
                .map(ExecutionEvent.class::cast)
                .toList();

        repository.appendEvents(runId, TENANT, events).await().indefinitely();

        ExecutionHistory history = repository.load(runId, TENANT).await().indefinitely();
        assertEquals(events.size(), history.getEvents().size());
        assertEquals("event-0", history.getEvents().getFirst().getEventId());
        assertEquals("event-999", history.getEvents().getLast().getEventId());
    }

    @Test
    void executionHistoryRepository_compactsAppendOnlyHistoryLogAfterThreshold() {
        FileExecutionHistoryRepository repository = new FileExecutionHistoryRepository(
                tempDir,
                FilePersistenceSupport.objectMapper(),
                1);
        WorkflowRunId runId = WorkflowRunId.of("run-history-compact");
        List<ExecutionEvent> events = IntStream.range(0, 16)
                .mapToObj(index -> new GenericExecutionEvent(
                        "compact-event-" + index,
                        runId,
                        "NodeCompletedEvent",
                        "event " + index,
                        Instant.now(),
                        Map.of("index", index)))
                .map(ExecutionEvent.class::cast)
                .toList();

        repository.appendEvents(runId, TENANT, events).await().indefinitely();

        assertTrue(Files.isRegularFile(historySnapshotPath(runId, TENANT)));
        assertFalse(Files.exists(historyLogPath(runId, TENANT)));
        ExecutionHistory history = repository.load(runId, TENANT).await().indefinitely();
        assertEquals(events.size(), history.getEvents().size());
        assertEquals("compact-event-15", history.getEvents().getLast().getEventId());
    }

    @Test
    void executionHistoryRepository_allowsDisablingAppendLogCompaction() {
        FileExecutionHistoryRepository repository = new FileExecutionHistoryRepository(
                tempDir,
                FilePersistenceSupport.objectMapper(),
                0);
        WorkflowRunId runId = WorkflowRunId.of("run-history-no-compact");
        List<ExecutionEvent> events = IntStream.range(0, 16)
                .mapToObj(index -> new GenericExecutionEvent(
                        "no-compact-event-" + index,
                        runId,
                        "NodeCompletedEvent",
                        "event " + index,
                        Instant.now(),
                        Map.of("index", index)))
                .map(ExecutionEvent.class::cast)
                .toList();

        repository.appendEvents(runId, TENANT, events).await().indefinitely();

        assertFalse(Files.exists(historySnapshotPath(runId, TENANT)));
        assertTrue(Files.isRegularFile(historyLogPath(runId, TENANT)));
        ExecutionHistory history = repository.load(runId, TENANT).await().indefinitely();
        assertEquals(events.size(), history.getEvents().size());
    }

    @Test
    void executionHistoryRepository_deduplicatesSnapshotAndStaleLogAfterCompaction() throws Exception {
        FileExecutionHistoryRepository repository = new FileExecutionHistoryRepository(tempDir);
        WorkflowRunId runId = WorkflowRunId.of("run-history-stale-log");
        Path snapshotPath = historySnapshotPath(runId);
        Files.createDirectories(snapshotPath.getParent());
        String eventJson = """
                {
                  "eventId": "duplicate-event",
                  "type": "NodeCompletedEvent",
                  "message": "same event",
                  "occurredAt": "2026-05-24T00:00:00Z",
                  "metadata": {}
                }
                """;
        Files.writeString(
                snapshotPath,
                """
                        {
                          "events": [
                            %s
                          ],
                          "updatedAt": "2026-05-24T00:00:00Z"
                        }
                        """.formatted(eventJson),
                StandardCharsets.UTF_8);
        Files.writeString(
                historyLogPath(runId),
                eventJson.replace('\n', ' ') + "\n",
                StandardCharsets.UTF_8);

        ExecutionHistory history = repository.load(runId).await().indefinitely();

        assertEquals(1, history.getEvents().size());
        assertEquals("duplicate-event", history.getEvents().getFirst().getEventId());
    }

    @Test
    void executionHistoryRepository_serializesConcurrentAppendsAcrossInstances() throws Exception {
        WorkflowRunId runId = WorkflowRunId.of("run-history-concurrent");
        List<FileExecutionHistoryRepository> repositories = IntStream.range(0, 4)
                .mapToObj(ignored -> new FileExecutionHistoryRepository(tempDir))
                .toList();
        int appendCount = 64;
        int concurrency = 16;
        ExecutorService executor = Executors.newFixedThreadPool(concurrency);
        CountDownLatch ready = new CountDownLatch(concurrency);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<CompletableFuture<Void>> appends = IntStream.range(0, appendCount)
                    .mapToObj(index -> CompletableFuture.runAsync(() -> {
                        ready.countDown();
                        awaitLatch(start);
                        repositories.get(index % repositories.size())
                                .append(runId, TENANT, "NodeCompletedEvent", "event-" + index, Map.of("index", index))
                                .await()
                                .indefinitely();
                    }, executor))
                    .toList();

            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            appends.forEach(CompletableFuture::join);
        } finally {
            start.countDown();
            executor.shutdownNow();
        }

        ExecutionHistory history = repositories.getFirst().load(runId, TENANT).await().indefinitely();
        assertEquals(appendCount, history.getEvents().size());
        assertEquals(appendCount, history.getEvents().stream()
                .map(ExecutionHistory.ExecutionEventHistory::getEventId)
                .distinct()
                .count());
    }

    private static WorkflowDefinition definition() {
        return definition(TENANT);
    }

    private static WorkflowDefinition definition(TenantId tenantId) {
        return definition(tenantId, WorkflowDefinitionId.of("wf-1"));
    }

    private static WorkflowDefinition definition(TenantId tenantId, WorkflowDefinitionId definitionId) {
        return new WorkflowDefinition(
                definitionId,
                tenantId,
                "test-workflow",
                "1.0.0",
                null,
                WorkflowMode.FLOW,
                List.of(node()),
                Map.of(),
                Map.of(),
                null,
                RetryPolicy.none(),
                CompensationPolicy.disabled());
    }

    private static NodeDefinition node() {
        return new NodeDefinition(
                NODE_ID,
                "node-1",
                NodeType.TASK,
                "local",
                Map.of(),
                List.of(),
                List.of(),
                RetryPolicy.none(),
                Duration.ZERO,
                false);
    }

    private static WorkflowRun restoredRun(TenantId tenantId, WorkflowRunId runId, Map<String, Object> variables) {
        WorkflowDefinition definition = definition(tenantId);
        return restoredRun(tenantId, runId, definition, variables);
    }

    private static WorkflowRun restoredRun(
            TenantId tenantId,
            WorkflowRunId runId,
            WorkflowDefinition definition,
            Map<String, Object> variables) {
        return WorkflowRun.restore(new WorkflowRunSnapshot(
                runId,
                tenantId,
                definition.id(),
                RunStatus.CREATED,
                variables,
                Map.of(),
                List.of(),
                null,
                Map.of(),
                null,
                Instant.now(),
                null,
                null,
                0),
                definition);
    }

    private static CallbackRegistration callback(String token, WorkflowRunId runId, long expiresInSeconds) {
        return new CallbackRegistration(token, runId, NODE_ID, "http://localhost/callback",
                Instant.now().plusSeconds(expiresInSeconds));
    }

    private Path historySnapshotPath(WorkflowRunId runId) {
        return tempDir.resolve("history").resolve(FilePersistenceSupport.fileName(runId.value()));
    }

    private Path historySnapshotPath(WorkflowRunId runId, TenantId tenantId) {
        return tempDir.resolve("history")
                .resolve("tenants")
                .resolve(FilePersistenceSupport.directoryName(tenantId.value()))
                .resolve("events")
                .resolve(FilePersistenceSupport.fileName(runId.value()));
    }

    private Path historyLogPath(WorkflowRunId runId) {
        return tempDir.resolve("history").resolve(historyLogFileName(runId));
    }

    private Path historyLogPath(WorkflowRunId runId, TenantId tenantId) {
        return tempDir.resolve("history")
                .resolve("tenants")
                .resolve(FilePersistenceSupport.directoryName(tenantId.value()))
                .resolve("events")
                .resolve(historyLogFileName(runId));
    }

    private String historyLogFileName(WorkflowRunId runId) {
        String jsonFileName = FilePersistenceSupport.fileName(runId.value());
        return jsonFileName.substring(0, jsonFileName.length() - ".json".length()) + ".jsonl";
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            assertTrue(latch.await(2, TimeUnit.SECONDS));
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new AssertionError(error);
        }
    }

    private Path executionTokenPath(String token) {
        return tempDir.resolve("tokens")
                .resolve("execution")
                .resolve(ExecutionTokenHash.sha256(token) + ".json");
    }

    private Path callbackPath(String token) {
        return tempDir.resolve("tokens")
                .resolve("callbacks")
                .resolve(BearerTokenHash.sha256(token) + ".json");
    }
}
