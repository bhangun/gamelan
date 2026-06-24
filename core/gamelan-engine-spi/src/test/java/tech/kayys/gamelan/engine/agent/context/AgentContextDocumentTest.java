package tech.kayys.gamelan.engine.agent.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import tech.kayys.gamelan.engine.tenant.TenantId;

class AgentContextDocumentTest {

    @Test
    void normalizesOptionalDocumentFields() {
        AgentContextDocument document = new AgentContextDocument(key(), null, null, null, null);

        assertEquals("", document.content());
        assertEquals("text/markdown", document.contentType());
        assertTrue(document.metadata().isEmpty());
    }

    @Test
    void rejectsUnsafeContentTypes() {
        assertThrows(IllegalArgumentException.class,
                () -> new AgentContextDocument(key(), "content", "text/plain\nx-bad: y", Map.of(), Instant.EPOCH));
        assertThrows(IllegalArgumentException.class,
                () -> new AgentContextDocument(key(), "content", "x".repeat(256), Map.of(), Instant.EPOCH));
    }

    @Test
    void rejectsUnsafeMetadataKeysAndNullValues() {
        Map<String, String> nullValue = new HashMap<>();
        nullValue.put("agent", null);

        assertThrows(IllegalArgumentException.class,
                () -> new AgentContextDocument(key(), "content", "text/markdown", Map.of("", "codex"), Instant.EPOCH));
        assertThrows(IllegalArgumentException.class,
                () -> new AgentContextDocument(key(), "content", "text/markdown", Map.of("bad\nkey", "codex"),
                        Instant.EPOCH));
        assertThrows(IllegalArgumentException.class,
                () -> new AgentContextDocument(key(), "content", "text/markdown", Map.of("x".repeat(129), "codex"),
                        Instant.EPOCH));
        assertThrows(IllegalArgumentException.class,
                () -> new AgentContextDocument(key(), "content", "text/markdown", nullValue, Instant.EPOCH));
    }

    @Test
    void acceptsStructuredMetadataKeys() {
        AgentContextDocument document = new AgentContextDocument(
                key(),
                "content",
                "text/markdown; charset=UTF-8",
                Map.of(
                        "agent.id", "codex",
                        "skill:java", "enabled",
                        "thread-turn", "7"),
                Instant.EPOCH);

        assertEquals("codex", document.metadata().get("agent.id"));
        assertEquals("enabled", document.metadata().get("skill:java"));
        assertEquals("7", document.metadata().get("thread-turn"));
    }

    private static AgentContextKey key() {
        return new AgentContextKey(
                TenantId.of("tenant-1"),
                "workspace-1",
                AgentContextScopes.THREAD,
                "threads/session.md");
    }
}
