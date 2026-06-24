package tech.kayys.gamelan.scheduler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import tech.kayys.gamelan.engine.node.NodeId;
import tech.kayys.gamelan.engine.tenant.TenantId;
import tech.kayys.gamelan.engine.workflow.WorkflowRunId;

class RetryEntriesTest {

    @Test
    void encodeDecode_preservesIdsContainingSeparators() {
        WorkflowRunId runId = WorkflowRunId.of("tenant:run/alpha.beta");
        NodeId nodeId = NodeId.of("agent:writer.step/1");

        String encoded = RetryEntries.encode(runId, nodeId);

        RetryEntries.Entry decoded = RetryEntries.decode(encoded).orElseThrow();
        assertEquals(runId, decoded.runId());
        assertNull(decoded.tenantId());
        assertEquals(nodeId, decoded.nodeId());
        assertNull(decoded.attempt());
    }

    @Test
    void encodeDecode_preservesTenantAwareEntries() {
        WorkflowRunId runId = WorkflowRunId.of("run:alpha.beta");
        TenantId tenantId = TenantId.of("tenant:one/two");
        NodeId nodeId = NodeId.of("agent:writer.step/1");

        String encoded = RetryEntries.encode(runId, tenantId, nodeId);

        RetryEntries.Entry decoded = RetryEntries.decode(encoded).orElseThrow();
        assertEquals(runId, decoded.runId());
        assertEquals(tenantId, decoded.tenantId());
        assertEquals(nodeId, decoded.nodeId());
        assertNull(decoded.attempt());
    }

    @Test
    void encodeDecode_preservesTenantAndAttemptAwareEntries() {
        WorkflowRunId runId = WorkflowRunId.of("run:alpha.beta");
        TenantId tenantId = TenantId.of("tenant:one/two");
        NodeId nodeId = NodeId.of("agent:writer.step/1");

        String encoded = RetryEntries.encode(runId, tenantId, nodeId, 3);

        RetryEntries.Entry decoded = RetryEntries.decode(encoded).orElseThrow();
        assertEquals(runId, decoded.runId());
        assertEquals(tenantId, decoded.tenantId());
        assertEquals(nodeId, decoded.nodeId());
        assertEquals(3, decoded.attempt());
    }

    @Test
    void encodeDecode_preservesAttemptAwareEntriesWithoutTenant() {
        WorkflowRunId runId = WorkflowRunId.of("run:alpha.beta");
        NodeId nodeId = NodeId.of("agent:writer.step/1");

        String encoded = RetryEntries.encode(runId, null, nodeId, 2);

        RetryEntries.Entry decoded = RetryEntries.decode(encoded).orElseThrow();
        assertEquals(runId, decoded.runId());
        assertNull(decoded.tenantId());
        assertEquals(nodeId, decoded.nodeId());
        assertEquals(2, decoded.attempt());
    }

    @Test
    void decode_rejectsMalformedEntries() {
        assertTrue(RetryEntries.decode("").isEmpty());
        assertTrue(RetryEntries.decode("missing-separator").isEmpty());
        assertTrue(RetryEntries.decode("too.many.parts").isEmpty());
        assertTrue(RetryEntries.decode("too.many.parts.here").isEmpty());
        assertTrue(RetryEntries.decode("%%%.$$$").isEmpty());
        assertTrue(RetryEntries.decode(RetryEntries.encode(WorkflowRunId.of("run-1"), null, NodeId.of("node-1"), 1)
                .replace("MQ", "MA")).isEmpty());
    }
}
