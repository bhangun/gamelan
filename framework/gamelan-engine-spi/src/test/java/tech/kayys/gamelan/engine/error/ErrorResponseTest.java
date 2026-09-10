package tech.kayys.gamelan.engine.error;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ErrorResponseTest {

    @Test
    void fromExceptionUsesGamelanException() {
        GamelanException ex = new GamelanException(ErrorCode.RUN_NOT_FOUND, "Run missing");
        ErrorResponse response = ErrorResponse.fromException(ex);

        assertEquals(ErrorCode.RUN_NOT_FOUND.getCode(), response.getErrorCode());
        assertEquals(ErrorCode.RUN_NOT_FOUND.getHttpStatus(), response.getHttpStatus());
        assertEquals("Run missing", response.getMessage());
        assertFalse(response.isRetryable());
    }

    @Test
    void fromExceptionDefaultsToInternal() {
        ErrorResponse response = ErrorResponse.fromException(new RuntimeException("boom"));
        assertEquals(ErrorCode.INTERNAL_ERROR.getCode(), response.getErrorCode());
        assertEquals(ErrorCode.INTERNAL_ERROR.getHttpStatus(), response.getHttpStatus());
        assertTrue(response.getMessage().toLowerCase().contains("internal"));
    }
}
