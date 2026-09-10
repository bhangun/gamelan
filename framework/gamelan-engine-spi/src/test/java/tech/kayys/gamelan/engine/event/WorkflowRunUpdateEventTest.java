package tech.kayys.gamelan.engine.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import tech.kayys.gamelan.engine.tenant.TenantId;
import tech.kayys.gamelan.engine.workflow.WorkflowRunId;

class WorkflowRunUpdateEventTest {

    @Test
    void of_preservesRunTenantAndReason() {
        WorkflowRunUpdateEvent event = WorkflowRunUpdateEvent.of(
                WorkflowRunId.of("run-1"),
                TenantId.of("tenant-1"),
                "run-resumed");

        assertEquals("run-1", event.runId());
        assertEquals("tenant-1", event.tenantId());
        assertEquals("run-resumed", event.reason());
        assertEquals(WorkflowRunId.of("run-1"), event.workflowRunId());
        assertEquals(TenantId.of("tenant-1"), event.tenant().orElseThrow());
    }

    @Test
    void constructor_normalizesOptionalFields() {
        WorkflowRunUpdateEvent event = new WorkflowRunUpdateEvent("  run-1  ", "  ", "  ");

        assertEquals("run-1", event.runId());
        assertNull(event.tenantId());
        assertEquals("updated", event.reason());
        assertTrue(event.tenant().isEmpty());
    }

    @Test
    void constructor_rejectsBlankRunId() {
        assertThrows(IllegalArgumentException.class, () -> new WorkflowRunUpdateEvent(" ", "tenant", "updated"));
    }
}
