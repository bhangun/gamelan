package tech.kayys.gamelan.engine.error;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ErrorCodeTest {

    @Test
    void fromCodeReturnsKnownCode() {
        assertEquals(ErrorCode.WORKFLOW_NOT_FOUND, ErrorCode.fromCode("WORKFLOW_001"));
    }

    @Test
    void fromCodeReturnsInternalOnUnknown() {
        assertEquals(ErrorCode.INTERNAL_ERROR, ErrorCode.fromCode("UNKNOWN_999"));
        assertEquals(ErrorCode.INTERNAL_ERROR, ErrorCode.fromCode(null));
    }
}
