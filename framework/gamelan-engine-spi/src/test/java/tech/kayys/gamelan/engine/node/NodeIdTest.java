package tech.kayys.gamelan.engine.node;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import tech.kayys.gamelan.engine.error.ErrorCode;
import tech.kayys.gamelan.engine.error.GamelanException;

class NodeIdTest {

    @Test
    void ofRejectsBlankNodeIds() {
        GamelanException error = assertThrows(GamelanException.class, () -> NodeId.of("   "));

        assertEquals(ErrorCode.VALIDATION_FAILED, error.getErrorCode());
        assertEquals("NodeId cannot be blank", error.getSafeMessage());
    }
}
