package tech.kayys.gamelan.engine.callback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import tech.kayys.gamelan.engine.error.ErrorCode;
import tech.kayys.gamelan.engine.error.GamelanException;

class CallbackConfigTest {

    @Test
    @SuppressWarnings("unchecked")
    void constructorDefensivelyCopiesAndFreezesPayloadMetadata() {
        Map<String, Object> nested = new HashMap<>();
        nested.put("decision", "approve");
        Map<String, Object> expectedPayload = new HashMap<>();
        expectedPayload.put("approval", nested);
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("agent", "local-coding-agent");

        CallbackConfig config = CallbackConfig.builder()
                .callbackUrl("https://example.test/callback")
                .expectedPayload(expectedPayload)
                .metadata(metadata)
                .build();

        expectedPayload.put("late", "ignored");
        nested.put("decision", "reject");
        metadata.put("agent", "mutated");

        assertFalse(config.getExpectedPayload().containsKey("late"));
        assertEquals(
                "approve",
                ((Map<String, Object>) config.getExpectedPayload().get("approval")).get("decision"));
        assertEquals("local-coding-agent", config.getMetadata().get("agent"));
        assertThrows(UnsupportedOperationException.class, () -> config.getMetadata().put("x", "y"));
        assertThrows(
                UnsupportedOperationException.class,
                () -> ((Map<String, Object>) config.getExpectedPayload().get("approval")).put("x", "y"));
    }

    @Test
    void constructorRejectsMissingCallbackUrl() {
        GamelanException error = assertThrows(
                GamelanException.class,
                () -> CallbackConfig.builder().callbackUrl(" ").build());

        assertEquals(ErrorCode.VALIDATION_FAILED, error.getErrorCode());
        assertEquals("Callback URL is required", error.getSafeMessage());
    }

    @Test
    void constructorRejectsInvalidRetryAndTimeoutPolicy() {
        GamelanException timeout = assertThrows(
                GamelanException.class,
                () -> CallbackConfig.builder()
                        .callbackUrl("https://example.test/callback")
                        .timeout(Duration.ZERO)
                        .build());
        GamelanException retries = assertThrows(
                GamelanException.class,
                () -> CallbackConfig.builder()
                        .callbackUrl("https://example.test/callback")
                        .maxRetries(-1)
                        .build());
        GamelanException retryDelay = assertThrows(
                GamelanException.class,
                () -> CallbackConfig.builder()
                        .callbackUrl("https://example.test/callback")
                        .retryDelay(Duration.ofMillis(-1))
                        .build());

        assertEquals("Callback timeout must be positive", timeout.getSafeMessage());
        assertEquals("Callback maxRetries cannot be negative", retries.getSafeMessage());
        assertEquals("Callback retryDelay cannot be negative", retryDelay.getSafeMessage());
    }
}
