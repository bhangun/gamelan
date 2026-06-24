package tech.kayys.gamelan.engine;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import tech.kayys.gamelan.engine.event.ExecutionEvent;
import tech.kayys.gamelan.engine.event.GenericExecutionEvent;
import tech.kayys.gamelan.engine.impl.InMemoryExecutionHistoryRepository;
import tech.kayys.gamelan.engine.execution.ExecutionHistory;
import tech.kayys.gamelan.engine.execution.ExecutionHistoryRepository;
import tech.kayys.gamelan.engine.execution.contract.ExecutionHistoryRepositoryCompensationMarkerContract;
import tech.kayys.gamelan.engine.execution.contract.ExecutionHistoryRepositoryHistoryContract;
import tech.kayys.gamelan.engine.execution.contract.ExecutionHistoryRepositoryIdempotencyMarkerContract;
import tech.kayys.gamelan.engine.node.NodeId;
import tech.kayys.gamelan.engine.tenant.TenantId;
import tech.kayys.gamelan.engine.workflow.WorkflowRunId;

import static org.junit.jupiter.api.Assertions.*;

class InMemoryExecutionHistoryRepositoryTest implements ExecutionHistoryRepositoryCompensationMarkerContract,
                ExecutionHistoryRepositoryHistoryContract,
                ExecutionHistoryRepositoryIdempotencyMarkerContract {

        InMemoryExecutionHistoryRepository repository;

        @BeforeEach
        void setUp() {
                repository = new InMemoryExecutionHistoryRepository();
        }

        @Override
        public ExecutionHistoryRepository newExecutionHistoryRepository() {
                return new InMemoryExecutionHistoryRepository();
        }

        @Test
        void append_whenCalled_storesEvent() {
                // Arrange
                WorkflowRunId runId = new WorkflowRunId("run1");
                String type = "TEST_EVENT";
                String message = "Test event message";
                Map<String, Object> metadata = Map.of("key1", "value1", "key2", 123);

                // Act
                repository.append(runId, type, message, metadata)
                                .await().indefinitely();

                // Load the history to verify
                ExecutionHistory history = repository.load(runId)
                                .await().indefinitely();

                // Assert
                assertNotNull(history);
                var events = history.getEvents();
                assertEquals(1, events.size());
        }

        @Test
        void append_multipleEvents_storesAllEvents() {
                // Arrange
                WorkflowRunId runId = new WorkflowRunId("run1");

                // Act
                repository.append(runId, "EVENT1", "Message 1", Map.of())
                                .await().indefinitely();
                repository.append(runId, "EVENT2", "Message 2", Map.of("data", "value"))
                                .await().indefinitely();

                // Load the history to verify
                ExecutionHistory history = repository.load(runId)
                                .await().indefinitely();

                // Assert
                assertNotNull(history);
                var events = history.getEvents();
                assertEquals(2, events.size());
        }

        @Test
        void appendEvents_whenNullOrEmpty_isNoop() {
                WorkflowRunId runId = new WorkflowRunId("run1");

                repository.appendEvents(runId, null).await().indefinitely();
                repository.appendEvents(runId, List.of()).await().indefinitely();

                ExecutionHistory history = repository.load(runId).await().indefinitely();
                assertTrue(history.getEvents().isEmpty());
        }

        @Test
        void appendSignalReceivedAudit_suppressesDuplicateSignalAuditRows() {
                WorkflowRunId runId = new WorkflowRunId("run-signal-audit");
                TenantId tenantId = TenantId.of("tenant-1");

                Boolean first = repository.appendSignalReceivedAudit(
                                runId,
                                tenantId,
                                "signal-key",
                                "approved",
                                Map.of("idempotencyKey", "signal-key"))
                                .await().indefinitely();
                Boolean second = repository.appendSignalReceivedAudit(
                                runId,
                                tenantId,
                                "signal-key",
                                "approved",
                                Map.of("idempotencyKey", "signal-key"))
                                .await().indefinitely();

                ExecutionHistory history = repository.load(runId, tenantId).await().indefinitely();
                assertTrue(first);
                assertFalse(second);
                assertEquals(1, history.getEvents().size());
                assertEquals(
                                ExecutionHistory.ExecutionEventHistory.ExecutionEventType.SIGNAL_RECEIVED,
                                history.getEvents().getFirst().getEventType());
        }

        @Test
        void appendSignalIgnoredAudit_suppressesDuplicateSignalAuditRows() {
                WorkflowRunId runId = new WorkflowRunId("run-ignored-signal-audit");
                TenantId tenantId = TenantId.of("tenant-1");

                Boolean first = repository.appendSignalIgnoredAudit(
                                runId,
                                tenantId,
                                "signal-key",
                                "Run is not accepting signals: CANCELLED",
                                Map.of("idempotencyKey", "signal-key"))
                                .await().indefinitely();
                Boolean second = repository.appendSignalIgnoredAudit(
                                runId,
                                tenantId,
                                "signal-key",
                                "Run is not accepting signals: CANCELLED",
                                Map.of("idempotencyKey", "signal-key"))
                                .await().indefinitely();

                ExecutionHistory history = repository.load(runId, tenantId).await().indefinitely();
                assertTrue(first);
                assertFalse(second);
                assertEquals(1, history.getEvents().size());
                assertEquals(
                                ExecutionHistory.ExecutionEventHistory.ExecutionEventType.SIGNAL_IGNORED,
                                history.getEvents().getFirst().getEventType());
        }

        @Test
        void appendEvents_concurrentAppendsAreNotLost() throws Exception {
                WorkflowRunId runId = new WorkflowRunId("run-concurrent-history");
                int appendCount = 256;
                int concurrency = 16;
                ExecutorService executor = Executors.newFixedThreadPool(concurrency);
                CountDownLatch ready = new CountDownLatch(concurrency);
                CountDownLatch start = new CountDownLatch(1);

                try {
                        List<CompletableFuture<Void>> appends = IntStream.range(0, appendCount)
                                        .mapToObj(index -> CompletableFuture.runAsync(() -> {
                                                ready.countDown();
                                                awaitLatch(start);
                                                ExecutionEvent event = new GenericExecutionEvent(
                                                                "event-" + index,
                                                                runId,
                                                                "NodeCompleted",
                                                                "event " + index,
                                                                Instant.EPOCH.plusMillis(index),
                                                                Map.of("index", index));
                                                repository.appendEvents(runId, List.of(event)).await().indefinitely();
                                        }, executor))
                                        .toList();

                        assertTrue(ready.await(5, TimeUnit.SECONDS));
                        start.countDown();
                        appends.forEach(CompletableFuture::join);
                } finally {
                        start.countDown();
                        executor.shutdownNow();
                }

                ExecutionHistory history = repository.load(runId).await().indefinitely();
                assertEquals(appendCount, history.getEvents().size());
                assertEquals(appendCount, history.getEvents().stream()
                                .map(ExecutionHistory.ExecutionEventHistory::getEventId)
                                .distinct()
                                .count());
        }

        @Test
        void load_whenNoEvents_returnsEmptyHistory() {
                // Arrange
                WorkflowRunId runId = new WorkflowRunId("nonexistent-run");

                // Act
                ExecutionHistory history = repository.load(runId)
                                .await().indefinitely();

                // Assert
                assertNotNull(history);
                assertTrue(history.getEvents().isEmpty());
        }

        @Test
        void isNodeResultProcessed_whenFirstTime_returnsFalse() {
                // Arrange
                WorkflowRunId runId = new WorkflowRunId("run1");
                NodeId nodeId = new NodeId("node1");
                int attempt = 1;

                // Act
                Boolean isProcessed = repository.isNodeResultProcessed(runId, nodeId, attempt)
                                .await().indefinitely();

                // Assert
                assertFalse(isProcessed);
        }

        @Test
        void markNodeResultProcessed_whenAlreadyProcessed_returnsTrue() {
                // Arrange
                WorkflowRunId runId = new WorkflowRunId("run1");
                NodeId nodeId = new NodeId("node1");
                int attempt = 1;

                Boolean firstCheck = repository.isNodeResultProcessed(runId, nodeId, attempt)
                                .await().indefinitely();
                assertFalse(firstCheck);

                Boolean firstMark = repository.markNodeResultProcessed(runId, nodeId, attempt)
                                .await().indefinitely();
                assertTrue(firstMark);

                Boolean secondCall = repository.isNodeResultProcessed(runId, nodeId, attempt)
                                .await().indefinitely();

                // Assert
                assertTrue(secondCall);

                Boolean secondMark = repository.markNodeResultProcessed(runId, nodeId, attempt)
                                .await().indefinitely();
                assertFalse(secondMark);
        }

        @Test
        void isNodeResultProcessed_differentAttempts_returnsIndependently() {
                // Arrange
                WorkflowRunId runId = new WorkflowRunId("run1");
                NodeId nodeId = new NodeId("node1");

                Boolean firstAttempt = repository.isNodeResultProcessed(runId, nodeId, 1)
                                .await().indefinitely();
                assertFalse(firstAttempt);

                Boolean secondAttempt = repository.isNodeResultProcessed(runId, nodeId, 2)
                                .await().indefinitely();
                assertFalse(secondAttempt);

                assertTrue(repository.markNodeResultProcessed(runId, nodeId, 1).await().indefinitely());
                assertTrue(repository.markNodeResultProcessed(runId, nodeId, 2).await().indefinitely());

                Boolean firstAttemptAgain = repository.isNodeResultProcessed(runId, nodeId, 1)
                                .await().indefinitely();
                assertTrue(firstAttemptAgain);

                Boolean secondAttemptAgain = repository.isNodeResultProcessed(runId, nodeId, 2)
                                .await().indefinitely();
                assertTrue(secondAttemptAgain);
        }

        @Test
        void isNodeResultProcessed_differentRuns_returnsIndependently() {
                // Arrange
                WorkflowRunId runId1 = new WorkflowRunId("run1");
                WorkflowRunId runId2 = new WorkflowRunId("run2");
                NodeId nodeId = new NodeId("node1");
                int attempt = 1;

                Boolean run1First = repository.isNodeResultProcessed(runId1, nodeId, attempt)
                                .await().indefinitely();
                assertFalse(run1First);

                Boolean run2First = repository.isNodeResultProcessed(runId2, nodeId, attempt)
                                .await().indefinitely();
                assertFalse(run2First);

                assertTrue(repository.markNodeResultProcessed(runId1, nodeId, attempt).await().indefinitely());
                assertTrue(repository.markNodeResultProcessed(runId2, nodeId, attempt).await().indefinitely());

                Boolean run1Second = repository.isNodeResultProcessed(runId1, nodeId, attempt)
                                .await().indefinitely();
                assertTrue(run1Second);

                Boolean run2Second = repository.isNodeResultProcessed(runId2, nodeId, attempt)
                                .await().indefinitely();
                assertTrue(run2Second);
        }

        @Test
        void tenantAwareOperations_isolateCollidingRunIds() {
                WorkflowRunId runId = new WorkflowRunId("shared-run");
                TenantId tenantA = TenantId.of("tenant-a");
                TenantId tenantB = TenantId.of("tenant-b");
                NodeId nodeId = new NodeId("node1");

                repository.append(runId, tenantA, "NodeCompletedEvent", "tenant-a event", Map.of())
                                .await().indefinitely();
                repository.append(runId, tenantB, "NodeFailedEvent", "tenant-b event", Map.of())
                                .await().indefinitely();

                ExecutionHistory.ExecutionEventHistory tenantAEvent = repository.load(runId, tenantA)
                                .await().indefinitely()
                                .getEvents()
                                .getFirst();
                ExecutionHistory.ExecutionEventHistory tenantBEvent = repository.load(runId, tenantB)
                                .await().indefinitely()
                                .getEvents()
                                .getFirst();

                assertEquals(ExecutionHistory.ExecutionEventHistory.ExecutionEventType.NODE_COMPLETED,
                                tenantAEvent.getEventType());
                assertEquals(ExecutionHistory.ExecutionEventHistory.ExecutionEventType.NODE_FAILED,
                                tenantBEvent.getEventType());
                assertTrue(repository.markNodeResultProcessed(runId, tenantA, nodeId, 1).await().indefinitely());
                assertFalse(repository.isNodeResultProcessed(runId, tenantB, nodeId, 1).await().indefinitely());
                assertTrue(repository.markNodeResultProcessed(runId, tenantB, nodeId, 1).await().indefinitely());
        }

        @Test
        void tenantAwareLoad_fallsBackToGlobalHistoryWhenTenantSpecificHistoryIsMissing() {
                WorkflowRunId runId = new WorkflowRunId("global-run");
                TenantId tenant = TenantId.of("tenant-a");

                repository.append(runId, "NodeCompleted", "global event", Map.of("source", "legacy"))
                                .await().indefinitely();

                ExecutionHistory history = repository.load(runId, tenant).await().indefinitely();

                assertEquals(1, history.getEvents().size());
                assertEquals("global event", history.getEvents().getFirst().getPayload().get("message"));
        }

        @Test
        void tenantAwareNodeMarkersHonorLegacyGlobalMarkers() {
                WorkflowRunId runId = new WorkflowRunId("legacy-node-marker-run");
                TenantId tenant = TenantId.of("tenant-a");
                NodeId nodeId = new NodeId("node1");

                assertTrue(repository.markNodeResultProcessed(runId, nodeId, 1).await().indefinitely());

                assertTrue(repository.isNodeResultProcessed(runId, tenant, nodeId, 1).await().indefinitely());
                assertFalse(repository.markNodeResultProcessed(runId, tenant, nodeId, 1).await().indefinitely());
        }

        @Test
        void externalSignalProcessedMarkers_areTenantScopedAndIdempotent() {
                WorkflowRunId runId = new WorkflowRunId("shared-run");
                TenantId tenantA = TenantId.of("tenant-a");
                TenantId tenantB = TenantId.of("tenant-b");
                String idempotencyKey = "callback-token-hash";

                assertFalse(repository.isExternalSignalProcessed(runId, tenantA, idempotencyKey)
                                .await().indefinitely());
                assertTrue(repository.markExternalSignalProcessed(runId, tenantA, idempotencyKey)
                                .await().indefinitely());
                assertTrue(repository.isExternalSignalProcessed(runId, tenantA, idempotencyKey)
                                .await().indefinitely());
                assertFalse(repository.isExternalSignalProcessed(runId, tenantB, idempotencyKey)
                                .await().indefinitely());
                assertFalse(repository.markExternalSignalProcessed(runId, tenantA, idempotencyKey)
                                .await().indefinitely());
        }

        @Test
        void tenantAwareSignalMarkersHonorLegacyGlobalMarkersAndRejectBlankKeys() {
                WorkflowRunId runId = new WorkflowRunId("legacy-signal-marker-run");
                TenantId tenant = TenantId.of("tenant-a");
                String idempotencyKey = "signal-token-hash";

                assertFalse(repository.markExternalSignalProcessed(runId, "").await().indefinitely());
                assertFalse(repository.markExternalSignalProcessed(runId, tenant, " ").await().indefinitely());
                assertTrue(repository.markExternalSignalProcessed(runId, idempotencyKey).await().indefinitely());

                assertTrue(repository.isExternalSignalProcessed(runId, tenant, idempotencyKey).await().indefinitely());
                assertFalse(repository.markExternalSignalProcessed(runId, tenant, idempotencyKey).await().indefinitely());
        }

        @Test
        void compensationNodeMarkers_areTenantScopedAndHonorLegacyGlobalMarkers() {
                WorkflowRunId runId = new WorkflowRunId("compensation-marker-run");
                TenantId tenantA = TenantId.of("tenant-a");
                TenantId tenantB = TenantId.of("tenant-b");
                NodeId nodeId = new NodeId("node1");

                assertFalse(repository.isCompensationNodeProcessed(runId, tenantA, nodeId)
                                .await().indefinitely());
                assertTrue(repository.markCompensationNodeProcessed(runId, tenantA, nodeId)
                                .await().indefinitely());
                assertTrue(repository.isCompensationNodeProcessed(runId, tenantA, nodeId)
                                .await().indefinitely());
                assertFalse(repository.isCompensationNodeProcessed(runId, tenantB, nodeId)
                                .await().indefinitely());
                assertTrue(repository.markCompensationNodeProcessed(runId, tenantB, nodeId)
                                .await().indefinitely());
                assertFalse(repository.markCompensationNodeProcessed(runId, tenantA, nodeId)
                                .await().indefinitely());

                NodeId legacyNodeId = new NodeId("legacy-node");
                assertTrue(repository.markCompensationNodeProcessed(runId, legacyNodeId).await().indefinitely());
                assertTrue(repository.isCompensationNodeProcessed(runId, tenantA, legacyNodeId)
                                .await().indefinitely());
                assertFalse(repository.markCompensationNodeProcessed(runId, tenantA, legacyNodeId)
                                .await().indefinitely());
        }

        private static void awaitLatch(CountDownLatch latch) {
                try {
                        latch.await();
                } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(error);
                }
        }
}
