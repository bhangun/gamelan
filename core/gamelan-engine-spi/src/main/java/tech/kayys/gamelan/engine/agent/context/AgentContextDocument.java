package tech.kayys.gamelan.engine.agent.context;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * Text-first persisted context for local or cloud agent workflows.
 */
public record AgentContextDocument(
        AgentContextKey key,
        String content,
        String contentType,
        Map<String, String> metadata,
        Instant updatedAt) {

    private static final String DEFAULT_CONTENT_TYPE = "text/markdown";
    private static final int MAX_CONTENT_TYPE_BYTES = 255;
    private static final int MAX_METADATA_KEY_BYTES = 128;

    public AgentContextDocument {
        Objects.requireNonNull(key, "AgentContextKey cannot be null");
        content = content != null ? content : "";
        contentType = requireContentType(contentType);
        metadata = metadata != null ? validateMetadata(metadata) : Map.of();
        updatedAt = updatedAt != null ? updatedAt : Instant.now();
    }

    private static String requireContentType(String value) {
        if (value == null || value.isBlank()) {
            return DEFAULT_CONTENT_TYPE;
        }
        if (utf8Bytes(value) > MAX_CONTENT_TYPE_BYTES) {
            throw new IllegalArgumentException(
                    "contentType exceeds max size of " + MAX_CONTENT_TYPE_BYTES + " bytes");
        }
        if (containsControlCharacter(value)) {
            throw new IllegalArgumentException("contentType cannot contain control characters");
        }
        return value;
    }

    private static Map<String, String> validateMetadata(Map<String, String> values) {
        values.forEach((key, value) -> {
            if (key == null || key.isBlank()) {
                throw new IllegalArgumentException("metadata keys cannot be null or blank");
            }
            if (utf8Bytes(key) > MAX_METADATA_KEY_BYTES) {
                throw new IllegalArgumentException(
                        "metadata key exceeds max size of " + MAX_METADATA_KEY_BYTES + " bytes: " + key);
            }
            if (containsControlCharacter(key)) {
                throw new IllegalArgumentException("metadata keys cannot contain control characters: " + key);
            }
            if (value == null) {
                throw new IllegalArgumentException("metadata values cannot be null: " + key);
            }
        });
        return Map.copyOf(values);
    }

    private static int utf8Bytes(String value) {
        return value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
    }

    private static boolean containsControlCharacter(String value) {
        return value.chars().anyMatch(character -> Character.isISOControl(character));
    }
}
