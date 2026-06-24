package tech.kayys.gamelan.engine.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import tech.kayys.gamelan.engine.error.ErrorCode;
import tech.kayys.gamelan.engine.error.GamelanException;

class BearerTokensTest {

    @Test
    void randomUrlSafeGeneratesDefaultEntropyToken() {
        String first = BearerTokens.randomUrlSafe();
        String second = BearerTokens.randomUrlSafe();

        assertTrue(first.matches("[A-Za-z0-9_-]{43}"));
        assertNotEquals(first, second);
    }

    @Test
    void randomUrlSafeRejectsWeakEntropy() {
        GamelanException error = assertThrows(
                GamelanException.class,
                () -> BearerTokens.randomUrlSafe(8));

        assertEquals(ErrorCode.VALIDATION_FAILED, error.getErrorCode());
        assertEquals("Bearer token entropy must be at least 16 bytes", error.getSafeMessage());
    }
}
