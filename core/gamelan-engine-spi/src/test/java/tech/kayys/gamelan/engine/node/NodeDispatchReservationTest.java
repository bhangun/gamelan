package tech.kayys.gamelan.engine.node;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import tech.kayys.gamelan.engine.tenant.TenantId;
import tech.kayys.gamelan.engine.workflow.WorkflowRunId;

class NodeDispatchReservationTest {

    private static final WorkflowRunId RUN_ID = WorkflowRunId.of("run-1");
    private static final TenantId TENANT_ID = TenantId.of("tenant-1");
    private static final NodeId NODE_ID = NodeId.of("node-1");

    @Test
    void reservedRequiresPositiveAttempt() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> NodeDispatchReservation.reserved(RUN_ID, TENANT_ID, NODE_ID, 0));

        assertEquals("Reserved node dispatch attempt must be positive", error.getMessage());
    }

    @Test
    void skippedCarriesReasonWithoutAttempt() {
        NodeDispatchReservation reservation = NodeDispatchReservation.skipped(
                RUN_ID,
                TENANT_ID,
                NODE_ID,
                "node-not-ready");

        assertFalse(reservation.reserved());
        assertEquals(0, reservation.attempt());
        assertEquals("node-not-ready", reservation.reason());
    }

    @Test
    void reservedFactoryCreatesAcceptedReservation() {
        NodeDispatchReservation reservation = NodeDispatchReservation.reserved(
                RUN_ID,
                TENANT_ID,
                NODE_ID,
                2);

        assertTrue(reservation.reserved());
        assertEquals(2, reservation.attempt());
        assertEquals("reserved", reservation.reason());
    }
}
