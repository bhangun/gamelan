package tech.kayys.gamelan.engine.callback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import tech.kayys.gamelan.engine.error.ErrorCode;
import tech.kayys.gamelan.engine.error.GamelanException;
import tech.kayys.gamelan.engine.node.NodeId;
import tech.kayys.gamelan.engine.workflow.WorkflowRunId;

class CallbackRegistrationTest {

    private static final WorkflowRunId RUN_ID = WorkflowRunId.of("run-1");
    private static final NodeId NODE_ID = NodeId.of("node-1");

    @Test
    void constructorRejectsBlankToken() {
        GamelanException error = assertThrows(
                GamelanException.class,
                () -> registration(" ", "https://example.test/callback"));

        assertEquals(ErrorCode.VALIDATION_FAILED, error.getErrorCode());
        assertEquals("Callback token cannot be blank", error.getSafeMessage());
    }

    @Test
    void constructorRejectsMissingCallbackUrl() {
        GamelanException error = assertThrows(
                GamelanException.class,
                () -> registration("callback-token", " "));

        assertEquals(ErrorCode.VALIDATION_FAILED, error.getErrorCode());
        assertEquals("Callback URL is required", error.getSafeMessage());
    }

    private static CallbackRegistration registration(String token, String callbackUrl) {
        return new CallbackRegistration(
                token,
                RUN_ID,
                NODE_ID,
                callbackUrl,
                Instant.now().plusSeconds(60));
    }
}
