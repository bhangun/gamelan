package tech.kayys.gamelan.engine.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

import tech.kayys.gamelan.engine.error.ErrorCode;
import tech.kayys.gamelan.engine.error.GamelanException;
import tech.kayys.gamelan.engine.node.NodeId;
import tech.kayys.gamelan.engine.tenant.TenantId;
import tech.kayys.gamelan.engine.workflow.WorkflowRunId;

class ExecutionTokenTest {

    private static final WorkflowRunId RUN_ID = WorkflowRunId.of("run-1");
    private static final TenantId TENANT_ID = TenantId.of("tenant-1");
    private static final NodeId NODE_ID = NodeId.of("node-1");

    @Test
    void constructorRejectsBlankTokenValue() {
        GamelanException error = assertThrows(GamelanException.class, () -> token("   ", 1));

        assertEquals(ErrorCode.VALIDATION_FAILED, error.getErrorCode());
        assertEquals("Token value cannot be blank", error.getSafeMessage());
    }

    @Test
    void constructorRejectsNonPositiveAttempt() {
        GamelanException error = assertThrows(GamelanException.class, () -> token("token-1", 0));

        assertEquals(ErrorCode.VALIDATION_FAILED, error.getErrorCode());
        assertEquals("ExecutionToken attempt must be positive", error.getSafeMessage());
    }

    @Test
    void createGeneratesUrlSafeRandomBearerToken() {
        ExecutionToken first = ExecutionToken.create(RUN_ID, NODE_ID, 1, Duration.ofMinutes(1));
        ExecutionToken second = ExecutionToken.create(RUN_ID, NODE_ID, 1, Duration.ofMinutes(1));

        assertEquals(RUN_ID, first.runId());
        assertEquals(NODE_ID, first.nodeId());
        assertEquals(1, first.attempt());
        assertTrue(first.token().matches("[A-Za-z0-9_-]{43}"));
        assertNotEquals(first.token(), second.token());
        assertTrue(first.expiresAt().isAfter(Instant.now()));
    }

    @Test
    void createCanBindTokenToTenant() {
        ExecutionToken token = ExecutionToken.create(RUN_ID, TENANT_ID, NODE_ID, 1, Duration.ofMinutes(1));

        assertEquals(TENANT_ID, token.tenantId());
    }

    @Test
    void createRejectsNonPositiveValidity() {
        GamelanException error = assertThrows(
                GamelanException.class,
                () -> ExecutionToken.create(RUN_ID, NODE_ID, 1, Duration.ZERO));

        assertEquals(ErrorCode.VALIDATION_FAILED, error.getErrorCode());
        assertEquals("ExecutionToken validity must be positive", error.getSafeMessage());
    }

    private static ExecutionToken token(String value, int attempt) {
        return new ExecutionToken(value, RUN_ID, NODE_ID, attempt, Instant.now().plusSeconds(60));
    }
}
