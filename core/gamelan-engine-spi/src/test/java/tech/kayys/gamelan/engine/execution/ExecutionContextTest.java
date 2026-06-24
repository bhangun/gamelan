package tech.kayys.gamelan.engine.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import tech.kayys.gamelan.engine.tenant.TenantId;
import tech.kayys.gamelan.engine.workflow.WorkflowRunId;

class ExecutionContextTest {

    private static final WorkflowRunId RUN_ID = WorkflowRunId.of("run-1");
    private static final TenantId TENANT_ID = TenantId.of("tenant-1");

    @Test
    @SuppressWarnings("unchecked")
    void constructorDefensivelyCopiesAndFreezesInitialVariables() {
        Map<String, Object> nested = new HashMap<>();
        nested.put("topic", "orders");
        List<String> tags = new ArrayList<>(List.of("agent"));

        Map<String, Object> variables = new HashMap<>();
        variables.put("nested", nested);
        variables.put("tags", tags);
        variables.put("array", new String[] { "first" });

        ExecutionContext context = new ExecutionContext(RUN_ID, TENANT_ID, variables);

        variables.put("late", "ignored");
        nested.put("topic", "mutated");
        tags.add("mutated");

        assertFalse(context.getVariables().containsKey("late"));
        assertEquals("orders", ((Map<String, Object>) context.getVariable("nested")).get("topic"));
        assertEquals(List.of("agent"), context.getVariable("tags"));
        assertEquals(List.of("first"), context.getVariable("array"));
        assertThrows(UnsupportedOperationException.class, () -> context.getVariables().put("x", "y"));
        assertThrows(UnsupportedOperationException.class,
                () -> ((Map<String, Object>) context.getVariable("nested")).put("x", "y"));
        assertThrows(UnsupportedOperationException.class,
                () -> ((List<String>) context.getVariable("tags")).add("x"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void mutatorsSnapshotMutablePayloadValues() {
        ExecutionContext context = new ExecutionContext(RUN_ID, TENANT_ID, Map.of());
        Map<String, Object> value = new HashMap<>();
        value.put("phase", "draft");

        context.setVariable("state", value);
        context.withMetadata("audit", value);
        context.withWorkflowState(Map.of("agent", value));

        value.put("phase", "mutated");

        assertEquals("draft", ((Map<String, Object>) context.getVariable("state")).get("phase"));
        assertEquals("draft", ((Map<String, Object>) context.getMetadata().get("audit")).get("phase"));
        assertEquals("draft", ((Map<String, Object>) context.getWorkflowState().get("agent")).get("phase"));
        assertThrows(UnsupportedOperationException.class,
                () -> ((Map<String, Object>) context.getMetadata().get("audit")).put("phase", "changed"));
        assertThrows(UnsupportedOperationException.class,
                () -> ((Map<String, Object>) context.getWorkflowState().get("agent")).put("phase", "changed"));
    }

    @Test
    void metadataAndWorkflowStateViewsAreReadOnly() {
        ExecutionContext context = new ExecutionContext(RUN_ID, TENANT_ID, Map.of());

        context.setMetadata(Map.of("source", "test"));
        context.setWorkflowState(Map.of("step", "waiting"));

        assertEquals("test", context.getMetadata().get("source"));
        assertEquals("waiting", context.getWorkflowState().get("step"));
        assertThrows(UnsupportedOperationException.class, () -> context.getMetadata().put("x", "y"));
        assertThrows(UnsupportedOperationException.class, () -> context.getWorkflowState().put("x", "y"));
        assertSame(context, context.withWorkflowState(null));
    }
}
