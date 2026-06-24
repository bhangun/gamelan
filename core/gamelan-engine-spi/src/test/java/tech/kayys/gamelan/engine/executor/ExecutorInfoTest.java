package tech.kayys.gamelan.engine.executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import tech.kayys.gamelan.engine.error.ErrorCode;
import tech.kayys.gamelan.engine.error.GamelanException;
import tech.kayys.gamelan.engine.protocol.CommunicationType;

class ExecutorInfoTest {

    @Test
    void constructorDefensivelyCopiesAndFreezesMetadata() {
        Map<String, String> metadata = new HashMap<>();
        metadata.put("version", "1.0.0");

        ExecutorInfo info = executor(metadata);

        metadata.put("version", "mutated");

        assertEquals("1.0.0", info.metadata().get("version"));
        assertThrows(UnsupportedOperationException.class, () -> info.metadata().put("x", "y"));
    }

    @Test
    void constructorNormalizesTextAndNullMetadata() {
        ExecutorInfo info = new ExecutorInfo(
                " executor-1 ",
                " agent ",
                CommunicationType.LOCAL,
                " local ",
                Duration.ofSeconds(10),
                null);

        assertEquals("executor-1", info.executorId());
        assertEquals("agent", info.executorType());
        assertEquals("local", info.endpoint());
        assertTrue(info.metadata().isEmpty());
    }

    @Test
    void constructorRejectsInvalidIdentityAndTransport() {
        assertInvalid("executorId", () -> new ExecutorInfo(
                " ",
                "agent",
                CommunicationType.LOCAL,
                "local",
                Duration.ZERO,
                Map.of()));
        assertInvalid("executorType", () -> new ExecutorInfo(
                "executor-1",
                "",
                CommunicationType.LOCAL,
                "local",
                Duration.ZERO,
                Map.of()));
        assertInvalid("communicationType", () -> new ExecutorInfo(
                "executor-1",
                "agent",
                null,
                "local",
                Duration.ZERO,
                Map.of()));
        assertInvalid("communicationType", () -> new ExecutorInfo(
                "executor-1",
                "agent",
                CommunicationType.UNSPECIFIED,
                "local",
                Duration.ZERO,
                Map.of()));
    }

    @Test
    void constructorRejectsInvalidTimeoutAndMetadata() {
        assertInvalid("timeout", () -> new ExecutorInfo(
                "executor-1",
                "agent",
                CommunicationType.LOCAL,
                "local",
                Duration.ofMillis(-1),
                Map.of()));

        Map<String, String> blankKey = new HashMap<>();
        blankKey.put(" ", "value");
        assertInvalid("metadata keys", () -> executor(blankKey));

        Map<String, String> nullValue = new HashMap<>();
        nullValue.put("version", null);
        assertInvalid("metadata value", () -> executor(nullValue));
    }

    @Test
    void updateFactoriesRetainIdentityAndRevalidateBoundary() {
        ExecutorInfo info = executor(Map.of("version", "1.0.0"));

        ExecutorInfo moved = info.withEndpoint(" grpc://executor ");
        ExecutorInfo updated = info.withMetadata(Map.of("profile", "agentic-local"));

        assertEquals(info.executorId(), moved.executorId());
        assertEquals("grpc://executor", moved.endpoint());
        assertEquals("agentic-local", updated.metadata().get("profile"));
        assertFalse(updated.metadata().containsKey("version"));
        assertInvalid("metadata keys", () -> info.withMetadata(Map.of("", "value")));
    }

    private static ExecutorInfo executor(Map<String, String> metadata) {
        return new ExecutorInfo(
                "executor-1",
                "agent",
                CommunicationType.LOCAL,
                "local",
                Duration.ofSeconds(10),
                metadata);
    }

    private static void assertInvalid(String expectedMessagePart, ThrowingRunnable operation) {
        GamelanException exception = assertThrows(GamelanException.class, operation::run);

        assertEquals(ErrorCode.VALIDATION_FAILED, exception.getErrorCode());
        assertFalse(exception.isRetryable());
        assertEquals(400, exception.getHttpStatusCode());
        assertTrue(exception.getSafeMessage().contains("Invalid executor info"));
        assertTrue(exception.getSafeMessage().contains(expectedMessagePart));
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run();
    }
}
