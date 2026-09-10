package tech.kayys.gamelan.engine.agent.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import io.smallrye.mutiny.Uni;
import tech.kayys.gamelan.engine.tenant.TenantId;

class AgentContextPageTest {

    @Test
    void unboundedPagesDoNotExposeContinuationCursor() {
        AgentContextPage page = AgentContextPage.from(
                new AgentContextQuery(TenantId.of("tenant-1"), "workspace-1", AgentContextScopes.THREAD),
                List.of(document("threads/session-1.md")));

        assertFalse(page.hasContinuation());
        assertTrue(page.nextQuery(new AgentContextQuery(
                TenantId.of("tenant-1"),
                "workspace-1",
                AgentContextScopes.THREAD)).isEmpty());
    }

    @Test
    void overFetchedBoundedPagesExposeCursorAndNextQuery() {
        AgentContextQuery query = new AgentContextQuery(
                TenantId.of("tenant-1"),
                "workspace-1",
                AgentContextScopes.THREAD,
                "threads/session-",
                2);
        AgentContextPage page = AgentContextPage.from(query, List.of(
                document("threads/session-1.md"),
                document("threads/session-2.md"),
                document("threads/session-3.md")));

        assertTrue(page.hasContinuation());
        assertEquals(2, page.documents().size());
        assertEquals(new AgentContextCursor(AgentContextScopes.THREAD, "threads/session-2.md"), page.nextCursor());

        AgentContextQuery nextQuery = page.nextQuery(query).orElseThrow();
        assertEquals(query.tenantId(), nextQuery.tenantId());
        assertEquals(query.workspaceId(), nextQuery.workspaceId());
        assertEquals(query.scope(), nextQuery.scope());
        assertEquals(query.pathPrefix(), nextQuery.pathPrefix());
        assertEquals(query.maxResults(), nextQuery.maxResults());
        assertEquals(page.nextCursor(), nextQuery.after());
    }

    @Test
    void partialBoundedPagesDoNotExposeContinuationCursor() {
        AgentContextPage page = AgentContextPage.from(
                new AgentContextQuery(
                        TenantId.of("tenant-1"),
                        "workspace-1",
                        AgentContextScopes.THREAD,
                        "threads/session-",
                        2),
                List.of(document("threads/session-1.md")));

        assertFalse(page.hasContinuation());
    }

    @Test
    void exactBoundedPagesDoNotGuessContinuation() {
        AgentContextPage page = AgentContextPage.from(
                new AgentContextQuery(
                        TenantId.of("tenant-1"),
                        "workspace-1",
                        AgentContextScopes.THREAD,
                        "threads/session-",
                        2),
                List.of(
                        document("threads/session-1.md"),
                        document("threads/session-2.md")));

        assertFalse(page.hasContinuation());
    }

    @Test
    void nextQueryUsesReturnedPageSizeWhenOriginalQueryWasUnbounded() {
        AgentContextQuery originalQuery = new AgentContextQuery(
                TenantId.of("tenant-1"),
                "workspace-1",
                AgentContextScopes.THREAD,
                "threads/session-");
        AgentContextQuery effectiveQuery = new AgentContextQuery(
                TenantId.of("tenant-1"),
                "workspace-1",
                AgentContextScopes.THREAD,
                "threads/session-",
                2);
        AgentContextPage page = AgentContextPage.from(effectiveQuery, List.of(
                document("threads/session-1.md"),
                document("threads/session-2.md"),
                document("threads/session-3.md")));

        AgentContextQuery nextQuery = page.nextQuery(originalQuery).orElseThrow();

        assertEquals(2, nextQuery.maxResults());
        assertEquals(new AgentContextCursor(AgentContextScopes.THREAD, "threads/session-2.md"), nextQuery.after());
    }

    @Test
    void storeDefaultListPageWrapsListResults() {
        AgentContextQuery query = new AgentContextQuery(
                TenantId.of("tenant-1"),
                "workspace-1",
                AgentContextScopes.THREAD,
                "threads/session-",
                1);
        AtomicReference<AgentContextQuery> storageQuery = new AtomicReference<>();
        AgentContextStore store = new InMemoryAgentContextStore(
                List.of(
                        document("threads/session-1.md"),
                        document("threads/session-2.md")),
                storageQuery);

        AgentContextPage page = store.listPage(query).await().indefinitely();

        assertEquals(1, page.documents().size());
        assertEquals(new AgentContextCursor(AgentContextScopes.THREAD, "threads/session-1.md"), page.nextCursor());
        assertEquals(2, storageQuery.get().maxResults());
    }

    private static AgentContextDocument document(String path) {
        return new AgentContextDocument(
                new AgentContextKey(TenantId.of("tenant-1"), "workspace-1", AgentContextScopes.THREAD, path),
                "assistant: context\n",
                "text/markdown",
                Map.of(),
                Instant.EPOCH);
    }

    private record InMemoryAgentContextStore(
            List<AgentContextDocument> documents,
            AtomicReference<AgentContextQuery> storageQuery) implements AgentContextStore {

        @Override
        public Uni<AgentContextDocument> save(AgentContextDocument document) {
            return Uni.createFrom().item(document);
        }

        @Override
        public Uni<Optional<AgentContextDocument>> load(AgentContextKey key) {
            return Uni.createFrom().item(Optional.empty());
        }

        @Override
        public Uni<AgentContextDocument> append(AgentContextKey key, String content, Map<String, String> metadata) {
            return Uni.createFrom().item(document(key.path()));
        }

        @Override
        public Uni<List<AgentContextDocument>> list(AgentContextQuery query) {
            storageQuery.set(query);
            return Uni.createFrom().item(documents);
        }

        @Override
        public Uni<Void> delete(AgentContextKey key) {
            return Uni.createFrom().voidItem();
        }
    }
}
