package tech.kayys.gamelan.engine.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

class ExecutionTokenHashTest {

    @Test
    void sha256ReturnsStableNonRawTokenValue() {
        String rawToken = "token-1";

        String first = ExecutionTokenHash.sha256(rawToken);
        String second = ExecutionTokenHash.sha256(rawToken);

        assertEquals(first, second);
        assertEquals(BearerTokenHash.sha256(rawToken), first);
        assertEquals(43, first.length());
        assertNotEquals(rawToken, first);
    }
}
