package tech.kayys.gamelan.engine.agent.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import tech.kayys.gamelan.engine.tenant.TenantId;

class AgentContextKeyTest {

    @Test
    void keyNormalizesBackslashPathsForOfflineTextFiles() {
        AgentContextKey key = new AgentContextKey(
                TenantId.of("tenant-1"),
                "workspace-1",
                AgentContextScopes.SKILL,
                "java\\SKILL.md");

        assertEquals("java/SKILL.md", key.path());
    }

    @Test
    void keyRejectsAmbiguousOrReservedLocalPaths() {
        assertInvalidPath(".");
        assertInvalidPath("threads/");
        assertInvalidPath("threads//session.md");
        assertInvalidPath("threads/./session.md");
        assertInvalidPath("threads/../session.md");
        assertInvalidPath("/AGENTS.md");
        assertInvalidPath("threads/session.md.lock");
        assertInvalidPath("threads/session.md.tmp");
        assertInvalidPath("threads/session.md.meta.properties");
    }

    @Test
    void queryRejectsCurrentDirectorySegments() {
        assertThrows(IllegalArgumentException.class,
                () -> new AgentContextQuery(TenantId.of("tenant-1"), ".", AgentContextScopes.THREAD));
        assertThrows(IllegalArgumentException.class,
                () -> new AgentContextQuery(TenantId.of("tenant-1"), "workspace-1", "."));
    }

    @Test
    void queryNormalizesAndValidatesPathPrefix() {
        AgentContextQuery query = new AgentContextQuery(
                TenantId.of("tenant-1"),
                "workspace-1",
                AgentContextScopes.THREAD,
                "threads\\session-");

        assertEquals("threads/session-", query.pathPrefix());
        assertThrows(IllegalArgumentException.class, () -> new AgentContextQuery(
                TenantId.of("tenant-1"),
                "workspace-1",
                AgentContextScopes.THREAD,
                "threads/../session"));
        assertThrows(IllegalArgumentException.class, () -> new AgentContextQuery(
                TenantId.of("tenant-1"),
                "workspace-1",
                AgentContextScopes.THREAD,
                "threads/session.md.tmp"));
    }

    @Test
    void queryValidatesMaxResults() {
        AgentContextQuery query = new AgentContextQuery(
                TenantId.of("tenant-1"),
                "workspace-1",
                AgentContextScopes.THREAD,
                "threads/",
                10);

        assertEquals(10, query.maxResults());
        assertThrows(IllegalArgumentException.class, () -> new AgentContextQuery(
                TenantId.of("tenant-1"),
                "workspace-1",
                AgentContextScopes.THREAD,
                null,
                0));
        assertThrows(IllegalArgumentException.class, () -> new AgentContextQuery(
                TenantId.of("tenant-1"),
                "workspace-1",
                AgentContextScopes.THREAD,
                null,
                -1));
    }

    @Test
    void cursorNormalizesAndValidatesPosition() {
        AgentContextCursor cursor = new AgentContextCursor(
                AgentContextScopes.THREAD,
                "threads\\session-1.md");

        assertEquals("threads/session-1.md", cursor.path());
        assertThrows(IllegalArgumentException.class,
                () -> new AgentContextCursor(".", "threads/session-1.md"));
        assertThrows(IllegalArgumentException.class,
                () -> new AgentContextCursor(AgentContextScopes.THREAD, "threads/../session-1.md"));
        assertThrows(IllegalArgumentException.class,
                () -> new AgentContextCursor(AgentContextScopes.THREAD, "threads/session-1.md.tmp"));
    }

    @Test
    void queryCursorMustMatchScopeAndPathPrefix() {
        AgentContextCursor cursor = new AgentContextCursor(
                AgentContextScopes.THREAD,
                "threads/session-1.md");

        AgentContextQuery query = new AgentContextQuery(
                TenantId.of("tenant-1"),
                "workspace-1",
                AgentContextScopes.THREAD,
                "threads/session-",
                10,
                cursor);

        assertEquals(cursor, query.after());
        assertThrows(IllegalArgumentException.class, () -> new AgentContextQuery(
                TenantId.of("tenant-1"),
                "workspace-1",
                AgentContextScopes.SKILL,
                null,
                10,
                cursor));
        assertThrows(IllegalArgumentException.class, () -> new AgentContextQuery(
                TenantId.of("tenant-1"),
                "workspace-1",
                AgentContextScopes.THREAD,
                "notes/",
                10,
                cursor));
    }

    private static void assertInvalidPath(String path) {
        assertThrows(IllegalArgumentException.class, () -> new AgentContextKey(
                TenantId.of("tenant-1"),
                "workspace-1",
                AgentContextScopes.THREAD,
                path));
    }
}
