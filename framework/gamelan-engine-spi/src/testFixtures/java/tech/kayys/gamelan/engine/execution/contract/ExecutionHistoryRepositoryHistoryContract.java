package tech.kayys.gamelan.engine.execution.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import tech.kayys.gamelan.engine.event.GenericExecutionEvent;
import tech.kayys.gamelan.engine.execution.ExecutionHistory;
import tech.kayys.gamelan.engine.execution.ExecutionHistoryRepository;
import tech.kayys.gamelan.engine.tenant.TenantId;
import tech.kayys.gamelan.engine.workflow.WorkflowRunId;

/**
 * Shared conformance tests for execution-history append and load behavior.
 */
public interface ExecutionHistoryRepositoryHistoryContract {

    ExecutionHistoryRepository newExecutionHistoryRepository();

    @Test
    default void historyContract_appendStoresEventsInOrderWithPayloadAndMetadata() {
        ExecutionHistoryRepository repository = newExecutionHistoryRepository();
        WorkflowRunId runId = WorkflowRunId.of("contract-history-append-run");

        repository.append(runId, "NodeCompletedEvent", "node completed", Map.of("sequence", 1))
                .await()
                .indefinitely();
        repository.append(runId, "NodeFailedEvent", "node failed", Map.of("sequence", 2))
                .await()
                .indefinitely();

        List<ExecutionHistory.ExecutionEventHistory> events = repository.load(runId)
                .await()
                .indefinitely()
                .getEvents();

        assertEquals(2, events.size());
        assertEquals(ExecutionHistory.ExecutionEventHistory.ExecutionEventType.NODE_COMPLETED,
                events.get(0).getEventType());
        assertEquals("node completed", events.get(0).getPayload().get("message"));
        assertEquals("NodeCompletedEvent", events.get(0).getMetadata().get("domainEventType"));
        assertEquals(1, events.get(0).getMetadata().get("sequence"));
        assertEquals(ExecutionHistory.ExecutionEventHistory.ExecutionEventType.NODE_FAILED,
                events.get(1).getEventType());
        assertEquals("node failed", events.get(1).getPayload().get("message"));
        assertEquals(2, events.get(1).getMetadata().get("sequence"));
    }

    @Test
    default void historyContract_appendEventsNullOrEmptyIsNoop() {
        ExecutionHistoryRepository repository = newExecutionHistoryRepository();
        WorkflowRunId runId = WorkflowRunId.of("contract-history-noop-run");
        TenantId tenant = TenantId.of("tenant-a");

        repository.appendEvents(runId, null).await().indefinitely();
        repository.appendEvents(runId, List.of()).await().indefinitely();
        repository.appendEvents(runId, tenant, null).await().indefinitely();
        repository.appendEvents(runId, tenant, List.of()).await().indefinitely();

        assertTrue(repository.load(runId).await().indefinitely().getEvents().isEmpty());
        assertTrue(repository.load(runId, tenant).await().indefinitely().getEvents().isEmpty());
    }

    @Test
    default void historyContract_appendEventsRejectsNullEventBeforeStoring() {
        ExecutionHistoryRepository repository = newExecutionHistoryRepository();
        WorkflowRunId runId = WorkflowRunId.of("contract-history-null-event-run");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> repository.appendEvents(runId, Collections.singletonList(null)).await().indefinitely());

        assertEquals("Execution history contains null event", error.getMessage());
        assertTrue(repository.load(runId).await().indefinitely().getEvents().isEmpty());
    }

    @Test
    default void historyContract_appendEventsRejectsMismatchedRunBeforeStoring() {
        ExecutionHistoryRepository repository = newExecutionHistoryRepository();
        WorkflowRunId runId = WorkflowRunId.of("contract-history-run-a");
        WorkflowRunId otherRunId = WorkflowRunId.of("contract-history-run-b");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> repository.appendEvents(runId, List.of(new GenericExecutionEvent(
                        "event-1",
                        otherRunId,
                        "NodeCompleted",
                        "completed",
                        Instant.EPOCH,
                        Map.of()))).await().indefinitely());

        assertEquals("Execution history event run id mismatch: expected contract-history-run-a but found "
                + "contract-history-run-b", error.getMessage());
        assertTrue(repository.load(runId).await().indefinitely().getEvents().isEmpty());
        assertTrue(repository.load(otherRunId).await().indefinitely().getEvents().isEmpty());
    }

    @Test
    default void historyContract_appendEventsRejectsTenantMismatchBeforeStoring() {
        ExecutionHistoryRepository repository = newExecutionHistoryRepository();
        WorkflowRunId runId = WorkflowRunId.of("contract-history-tenant-mismatch-run");
        TenantId tenant = TenantId.of("tenant-a");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> repository.appendEvents(runId, tenant, List.of(new GenericExecutionEvent(
                        "event-1",
                        runId,
                        "NodeCompleted",
                        "completed",
                        Instant.EPOCH,
                        Map.of("tenantId", "tenant-b")))).await().indefinitely());

        assertEquals("Execution history event tenant id mismatch: expected tenant-a but found tenant-b",
                error.getMessage());
        assertTrue(repository.load(runId, tenant).await().indefinitely().getEvents().isEmpty());
    }

    @Test
    default void historyContract_tenantHistoryIsIsolatedAndFallsBackToLegacyGlobalHistory() {
        ExecutionHistoryRepository repository = newExecutionHistoryRepository();
        WorkflowRunId sharedRunId = WorkflowRunId.of("contract-history-shared-run");
        TenantId tenantA = TenantId.of("tenant-a");
        TenantId tenantB = TenantId.of("tenant-b");

        repository.append(sharedRunId, tenantA, "NodeCompletedEvent", "tenant-a event", Map.of("tenant", "a"))
                .await()
                .indefinitely();
        repository.append(sharedRunId, tenantB, "NodeFailedEvent", "tenant-b event", Map.of("tenant", "b"))
                .await()
                .indefinitely();

        ExecutionHistory tenantAHistory = repository.load(sharedRunId, tenantA).await().indefinitely();
        ExecutionHistory tenantBHistory = repository.load(sharedRunId, tenantB).await().indefinitely();

        assertEquals(1, tenantAHistory.getEvents().size());
        assertEquals(1, tenantBHistory.getEvents().size());
        assertEquals("tenant-a event", tenantAHistory.getEvents().getFirst().getPayload().get("message"));
        assertEquals("a", tenantAHistory.getEvents().getFirst().getMetadata().get("tenant"));
        assertEquals("tenant-b event", tenantBHistory.getEvents().getFirst().getPayload().get("message"));
        assertEquals("b", tenantBHistory.getEvents().getFirst().getMetadata().get("tenant"));
        assertTrue(repository.load(sharedRunId).await().indefinitely().getEvents().isEmpty());

        WorkflowRunId legacyRunId = WorkflowRunId.of("contract-history-legacy-global-run");
        repository.append(legacyRunId, "WorkflowStartedEvent", "legacy global event", Map.of("source", "legacy"))
                .await()
                .indefinitely();

        ExecutionHistory fallbackHistory = repository.load(legacyRunId, tenantA).await().indefinitely();
        assertEquals(1, fallbackHistory.getEvents().size());
        assertEquals("legacy global event", fallbackHistory.getEvents().getFirst().getPayload().get("message"));
        assertEquals("legacy", fallbackHistory.getEvents().getFirst().getMetadata().get("source"));
    }

    @Test
    default void historyContract_genericEventPreservesAuditMetadataAndPayloadOverride() {
        ExecutionHistoryRepository repository = newExecutionHistoryRepository();
        WorkflowRunId runId = WorkflowRunId.of("contract-history-generic-event-run");
        TenantId tenant = TenantId.of("tenant-a");

        repository.appendEvents(runId, tenant, List.of(new GenericExecutionEvent(
                "generic-event-1",
                runId,
                "AgentReasoning",
                "agent selected browser tool",
                Instant.EPOCH,
                Map.of(
                        "agentId", "agent-1",
                        "tool", "browser",
                        ExecutionHistory.DOMAIN_EVENT_PAYLOAD_METADATA_KEY,
                        Map.of("reason", "needs-page-state")))))
                .await()
                .indefinitely();

        ExecutionHistory.ExecutionEventHistory event = repository.load(runId, tenant)
                .await()
                .indefinitely()
                .getEvents()
                .getFirst();

        assertEquals("generic-event-1", event.getEventId());
        assertEquals(ExecutionHistory.ExecutionEventHistory.ExecutionEventType.STATE_UPDATED, event.getEventType());
        assertEquals("needs-page-state", event.getPayload().get("reason"));
        assertFalse(event.getPayload().containsKey("message"));
        assertEquals("AgentReasoning", event.getMetadata().get("domainEventType"));
        assertEquals("agent-1", event.getMetadata().get("agentId"));
        assertEquals("browser", event.getMetadata().get("tool"));
        assertFalse(event.getMetadata().containsKey(ExecutionHistory.DOMAIN_EVENT_PAYLOAD_METADATA_KEY));
    }
}
