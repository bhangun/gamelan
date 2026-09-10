package tech.kayys.gamelan.engine.saga;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;

import org.junit.jupiter.api.Test;

import tech.kayys.gamelan.engine.error.ErrorInfo;

class CompensationErrorsTest {

    @Test
    void failed_usesCanonicalCompensationFailureCodeAndMessageFallback() {
        ErrorInfo error = CompensationErrors.failed("");

        assertEquals(CompensationErrors.COMPENSATION_FAILED, error.code());
        assertEquals(CompensationErrors.DEFAULT_COMPENSATION_FAILED_MESSAGE, error.message());
        assertEquals("", error.stackTrace());
        assertEquals(Map.of(), error.context());
    }

    @Test
    void normalizeFailure_preservesSpecificErrorWhenProvided() {
        ErrorInfo error = new ErrorInfo("ROLLBACK_DENIED", "rollback denied", "stack", Map.of("nodeId", "n1"));

        ErrorInfo normalized = CompensationErrors.normalizeFailure(error);

        assertEquals("ROLLBACK_DENIED", normalized.code());
        assertEquals("rollback denied", normalized.message());
        assertEquals("stack", normalized.stackTrace());
        assertEquals(Map.of("nodeId", "n1"), normalized.context());
    }

    @Test
    void normalizeFailure_fillsMissingFailureFields() {
        ErrorInfo error = new ErrorInfo("", " ", null, Map.of("nodeId", "n1"));

        ErrorInfo normalized = CompensationErrors.normalizeFailure(error);

        assertEquals(CompensationErrors.COMPENSATION_FAILED, normalized.code());
        assertEquals(CompensationErrors.DEFAULT_COMPENSATION_FAILED_MESSAGE, normalized.message());
        assertEquals("", normalized.stackTrace());
        assertEquals(Map.of("nodeId", "n1"), normalized.context());
    }
}
