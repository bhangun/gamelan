package tech.kayys.gamelan.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.sqlclient.Pool;
import io.vertx.mutiny.sqlclient.PreparedQuery;
import io.vertx.mutiny.sqlclient.Tuple;
import tech.kayys.gamelan.engine.agent.context.AgentContextCursor;
import tech.kayys.gamelan.engine.agent.context.AgentContextDocument;
import tech.kayys.gamelan.engine.agent.context.AgentContextKey;
import tech.kayys.gamelan.engine.agent.context.AgentContextQuery;
import tech.kayys.gamelan.engine.agent.context.AgentContextScopes;
import tech.kayys.gamelan.engine.tenant.TenantId;

class PostgresAgentContextStoreTest {

    private Pool pgPool;

    @SuppressWarnings("rawtypes")
    private PreparedQuery preparedQuery;

    private PostgresAgentContextStore store;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        pgPool = mock(Pool.class);
        preparedQuery = mock(PreparedQuery.class);

        when(pgPool.preparedQuery(anyString())).thenReturn(preparedQuery);
        when(preparedQuery.execute(any(Tuple.class))).thenReturn(Uni.createFrom().nullItem());

        store = new PostgresAgentContextStore();
        store.pgPool = pgPool;
        store.objectMapper = new ObjectMapper();
    }

    @Test
    void save_usesTenantWorkspaceScopedUpsert() {
        store.save(new AgentContextDocument(
                key(),
                "system: keep context\n",
                "text/markdown",
                Map.of("agent", "codex"),
                Instant.parse("2026-05-24T00:00:00Z")));

        String sql = capturedSql();
        assertTrue(sql.contains("INSERT INTO agent_context_documents"));
        assertTrue(sql.contains("ON CONFLICT (tenant_id, workspace_id, scope, document_path)"));
        assertTrue(sql.contains("content = EXCLUDED.content"));
    }

    @Test
    void append_returnsPersistedDocumentAndMergesMetadata() {
        store.append(key(), "assistant: next step\n", Map.of("turn", "2"));

        String sql = capturedSql();
        assertTrue(sql.contains("agent_context_documents.content || EXCLUDED.content"));
        assertTrue(sql.contains("agent_context_documents.metadata || EXCLUDED.metadata"));
        assertTrue(sql.contains("octet_length(agent_context_documents.content)"));
        assertTrue(sql.contains("octet_length(EXCLUDED.content) <= $8::bigint"));
        assertTrue(sql.contains("octet_length((agent_context_documents.metadata || EXCLUDED.metadata)::text) <= $9::bigint"));
        assertTrue(sql.contains("RETURNING tenant_id, workspace_id, scope, document_path"));
    }

    @Test
    void load_scopesByFullDocumentKey() {
        store.load(key());

        String sql = capturedSql();
        assertTrue(sql.contains("WHERE tenant_id = $1 AND workspace_id = $2 AND scope = $3 AND document_path = $4"));
    }

    @Test
    void listSupportsOptionalScopeWithinTenantWorkspace() {
        store.list(new AgentContextQuery(TenantId.of("tenant-1"), "workspace-1", AgentContextScopes.THREAD));

        String sql = capturedSql();
        assertTrue(sql.contains("WHERE tenant_id = $1"));
        assertTrue(sql.contains("AND workspace_id = $2"));
        assertTrue(sql.contains("AND ($3::text IS NULL OR scope = $3)"));
        assertTrue(sql.contains("AND ($4::text IS NULL OR left(document_path, length($4)) = $4)"));
        assertTrue(sql.contains("AND ($6::text IS NULL OR scope > $6 OR (scope = $6 AND document_path > $7))"));
        assertTrue(sql.contains("ORDER BY scope, document_path"));
        assertTrue(sql.contains("LIMIT $5::int"));
        assertEquals(1000, capturedTuple().getValue(4));
    }

    @Test
    void listSupportsLiteralPathPrefixWithinTenantWorkspace() {
        store.list(new AgentContextQuery(
                TenantId.of("tenant-1"),
                "workspace-1",
                AgentContextScopes.THREAD,
                "threads/session-"));

        String sql = capturedSql();
        assertTrue(sql.contains("left(document_path, length($4)) = $4"));
    }

    @Test
    void listSupportsBoundedResultCount() {
        store.list(new AgentContextQuery(
                TenantId.of("tenant-1"),
                "workspace-1",
                AgentContextScopes.THREAD,
                "threads/session-",
                25));

        String sql = capturedSql();
        assertTrue(sql.contains("LIMIT $5::int"));
        assertEquals(25, capturedTuple().getValue(4));
    }

    @Test
    void listPageUsesConfiguredDefaultLimitAndOverFetchesContinuationProbe() {
        store.maxListResults = 2;

        store.listPage(new AgentContextQuery(
                TenantId.of("tenant-1"),
                "workspace-1",
                AgentContextScopes.THREAD,
                "threads/session-"));

        String sql = capturedSql();
        assertTrue(sql.contains("LIMIT $5::int"));
        assertEquals(3, capturedTuple().getValue(4));
    }

    @Test
    void listSupportsCursorPaginationAfterDeterministicOrdering() {
        store.list(new AgentContextQuery(
                TenantId.of("tenant-1"),
                "workspace-1",
                AgentContextScopes.THREAD,
                "threads/session-",
                25,
                new AgentContextCursor(AgentContextScopes.THREAD, "threads/session-1.md")));

        String sql = capturedSql();
        assertTrue(sql.contains("AND ($6::text IS NULL OR scope > $6 OR (scope = $6 AND document_path > $7))"));
        assertTrue(sql.contains("ORDER BY scope, document_path"));
        assertTrue(sql.contains("LIMIT $5::int"));
        assertEquals(25, capturedTuple().getValue(4));
    }

    @Test
    void listRejectsMaxResultsAboveConfiguredLimitBeforeSqlExecution() {
        store.maxListResults = 2;

        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> store.list(
                new AgentContextQuery(
                        TenantId.of("tenant-1"),
                        "workspace-1",
                        AgentContextScopes.THREAD,
                        null,
                        3)));

        verify(pgPool, never()).preparedQuery(anyString());
    }

    @Test
    void listPageRejectsMaxResultsAboveConfiguredLimitBeforeSqlExecution() {
        store.maxListResults = 2;

        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> store.listPage(
                new AgentContextQuery(
                        TenantId.of("tenant-1"),
                        "workspace-1",
                        AgentContextScopes.THREAD,
                        null,
                        3)));

        verify(pgPool, never()).preparedQuery(anyString());
    }

    @Test
    void saveRejectsDocumentsExceedingConfiguredByteLimitBeforeSqlExecution() {
        store.maxDocumentBytes = 5;

        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> store.save(
                new AgentContextDocument(
                        key(),
                        "123456",
                        "text/markdown",
                        Map.of(),
                        Instant.EPOCH)));

        verify(pgPool, never()).preparedQuery(anyString());
    }

    @Test
    void saveRejectsMetadataExceedingConfiguredByteLimitBeforeSqlExecution() {
        store.maxMetadataBytes = 5;

        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> store.save(
                new AgentContextDocument(
                        key(),
                        "ok",
                        "text/markdown",
                        Map.of("blob", "123456"),
                        Instant.EPOCH)));

        verify(pgPool, never()).preparedQuery(anyString());
    }

    @Test
    void appendRejectsFragmentsExceedingConfiguredByteLimitBeforeSqlExecution() {
        store.maxDocumentBytes = 5;

        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> store.append(key(), "123456", Map.of()));

        verify(pgPool, never()).preparedQuery(anyString());
    }

    @Test
    void appendRejectsMetadataExceedingConfiguredByteLimitBeforeSqlExecution() {
        store.maxMetadataBytes = 5;

        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> store.append(key(), "ok", Map.of("blob", "123456")));

        verify(pgPool, never()).preparedQuery(anyString());
    }

    @Test
    void delete_scopesByFullDocumentKey() {
        store.delete(key());

        String sql = capturedSql();
        assertTrue(sql.contains("DELETE FROM agent_context_documents"));
        assertTrue(sql.contains("WHERE tenant_id = $1 AND workspace_id = $2 AND scope = $3 AND document_path = $4"));
    }

    private AgentContextKey key() {
        return new AgentContextKey(
                TenantId.of("tenant-1"),
                "workspace-1",
                AgentContextScopes.THREAD,
                "threads/session-1.md");
    }

    private String capturedSql() {
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(pgPool).preparedQuery(sql.capture());
        return sql.getValue();
    }

    private Tuple capturedTuple() {
        ArgumentCaptor<Tuple> tuple = ArgumentCaptor.forClass(Tuple.class);
        verify(preparedQuery).execute(tuple.capture());
        return tuple.getValue();
    }
}
