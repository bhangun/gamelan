package tech.kayys.gamelan.repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Objects;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import io.quarkus.arc.properties.IfBuildProperty;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.sqlclient.Pool;
import io.vertx.mutiny.sqlclient.Row;
import io.vertx.mutiny.sqlclient.RowSet;
import io.vertx.mutiny.sqlclient.Tuple;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import tech.kayys.gamelan.engine.agent.context.AgentContextCursor;
import tech.kayys.gamelan.engine.agent.context.AgentContextDocument;
import tech.kayys.gamelan.engine.agent.context.AgentContextKey;
import tech.kayys.gamelan.engine.agent.context.AgentContextPage;
import tech.kayys.gamelan.engine.agent.context.AgentContextQuery;
import tech.kayys.gamelan.engine.agent.context.AgentContextStore;
import tech.kayys.gamelan.engine.tenant.TenantId;

/**
 * PostgreSQL-backed store for server/cloud agent context.
 */
@ApplicationScoped
@IfBuildProperty(name = "quarkus.datasource.db-kind", stringValue = "postgresql")
@IfBuildProperty(name = "gamelan.agent.context.store", stringValue = "postgres")
public class PostgresAgentContextStore implements AgentContextStore {

    private static final long DEFAULT_MAX_DOCUMENT_BYTES = 8L * 1024L * 1024L;
    private static final long DEFAULT_MAX_METADATA_BYTES = 256L * 1024L;
    private static final int DEFAULT_MAX_LIST_RESULTS = 1000;
    private static final TypeReference<Map<String, String>> STRING_MAP = new TypeReference<>() {
    };

    @Inject
    Pool pgPool;

    @Inject
    ObjectMapper objectMapper;

    @ConfigProperty(name = "gamelan.agent.context.max-document-bytes", defaultValue = "8388608")
    long maxDocumentBytes = DEFAULT_MAX_DOCUMENT_BYTES;

    @ConfigProperty(name = "gamelan.agent.context.max-metadata-bytes", defaultValue = "262144")
    long maxMetadataBytes = DEFAULT_MAX_METADATA_BYTES;

    @ConfigProperty(name = "gamelan.agent.context.max-list-results", defaultValue = "1000")
    int maxListResults = DEFAULT_MAX_LIST_RESULTS;

    @Override
    public Uni<AgentContextDocument> save(AgentContextDocument document) {
        requireContentWithinLimit(document.content());
        String metadataJson = toJson(document.metadata());
        requireMetadataWithinLimit(metadataJson);
        String sql = """
                INSERT INTO agent_context_documents
                    (tenant_id, workspace_id, scope, document_path, content, content_type, metadata, updated_at)
                VALUES ($1, $2, $3, $4, $5, $6, $7::jsonb, $8)
                ON CONFLICT (tenant_id, workspace_id, scope, document_path)
                DO UPDATE SET
                    content = EXCLUDED.content,
                    content_type = EXCLUDED.content_type,
                    metadata = EXCLUDED.metadata,
                    updated_at = EXCLUDED.updated_at
                """;

        return pgPool.preparedQuery(sql)
                .execute(Tuple.tuple()
                        .addString(document.key().tenantId().value())
                        .addString(document.key().workspaceId())
                        .addString(document.key().scope())
                        .addString(document.key().path())
                        .addString(document.content())
                        .addString(document.contentType())
                        .addString(metadataJson)
                        .addValue(document.updatedAt()))
                .replaceWith(document);
    }

    @Override
    public Uni<Optional<AgentContextDocument>> load(AgentContextKey key) {
        String sql = """
                SELECT tenant_id, workspace_id, scope, document_path, content, content_type, metadata, updated_at
                FROM agent_context_documents
                WHERE tenant_id = $1 AND workspace_id = $2 AND scope = $3 AND document_path = $4
                """;

        return pgPool.preparedQuery(sql)
                .execute(Tuple.of(key.tenantId().value(), key.workspaceId(), key.scope(), key.path()))
                .map(rows -> firstRow(rows).map(this::toDocument));
    }

    @Override
    public Uni<AgentContextDocument> append(AgentContextKey key, String content, Map<String, String> metadata) {
        String safeContent = content != null ? content : "";
        requireContentWithinLimit(safeContent);
        Map<String, String> safeMetadata = metadata != null ? metadata : Map.of();
        String metadataJson = toJson(safeMetadata);
        requireMetadataWithinLimit(metadataJson);
        Instant updatedAt = Instant.now();
        String sql = """
                INSERT INTO agent_context_documents
                    (tenant_id, workspace_id, scope, document_path, content, content_type, metadata, updated_at)
                VALUES ($1, $2, $3, $4, $5, 'text/markdown', $6::jsonb, $7)
                ON CONFLICT (tenant_id, workspace_id, scope, document_path)
                DO UPDATE SET
                    content = agent_context_documents.content || EXCLUDED.content,
                    metadata = agent_context_documents.metadata || EXCLUDED.metadata,
                    updated_at = EXCLUDED.updated_at
                WHERE octet_length(agent_context_documents.content) + octet_length(EXCLUDED.content) <= $8::bigint
                  AND octet_length((agent_context_documents.metadata || EXCLUDED.metadata)::text) <= $9::bigint
                RETURNING tenant_id, workspace_id, scope, document_path, content, content_type, metadata, updated_at
                """;

        return pgPool.preparedQuery(sql)
                .execute(Tuple.tuple()
                        .addString(key.tenantId().value())
                        .addString(key.workspaceId())
                        .addString(key.scope())
                        .addString(key.path())
                        .addString(safeContent)
                        .addString(metadataJson)
                        .addValue(updatedAt)
                        .addLong(maxDocumentBytes)
                        .addLong(maxMetadataBytes))
                .map(rows -> firstRow(rows)
                        .map(this::toDocument)
                        .orElseThrow(() -> new IllegalArgumentException(
                                "Agent context document or metadata exceeds configured size limits")));
    }

    @Override
    public Uni<List<AgentContextDocument>> list(AgentContextQuery query) {
        return list(query, maxListResults);
    }

    @Override
    public Uni<AgentContextPage> listPage(AgentContextQuery query) {
        AgentContextQuery requestedQuery = effectivePageQuery(query);
        AgentContextQuery storageQuery = continuationProbeQuery(requestedQuery);
        return list(storageQuery, continuationProbeLimit())
                .map(documents -> AgentContextPage.from(requestedQuery, documents));
    }

    private Uni<List<AgentContextDocument>> list(AgentContextQuery query, int maxAllowedResults) {
        Objects.requireNonNull(query, "AgentContextQuery cannot be null");
        int listLimit = listLimit(query, maxAllowedResults);
        String sql = """
                SELECT tenant_id, workspace_id, scope, document_path, content, content_type, metadata, updated_at
                FROM agent_context_documents
                WHERE tenant_id = $1
                  AND workspace_id = $2
                  AND ($3::text IS NULL OR scope = $3)
                  AND ($4::text IS NULL OR left(document_path, length($4)) = $4)
                  AND ($6::text IS NULL OR scope > $6 OR (scope = $6 AND document_path > $7))
                ORDER BY scope, document_path
                LIMIT $5::int
                """;

        AgentContextCursor after = query.after();
        return pgPool.preparedQuery(sql)
                .execute(Tuple.tuple()
                        .addString(query.tenantId().value())
                        .addString(query.workspaceId())
                        .addString(query.scope())
                        .addString(query.pathPrefix())
                        .addInteger(listLimit)
                        .addString(after != null ? after.scope() : null)
                        .addString(after != null ? after.path() : null))
                .map(rows -> {
                    ArrayList<AgentContextDocument> documents = new ArrayList<>();
                    for (Row row : rows) {
                        documents.add(toDocument(row));
                    }
                    return documents;
                });
    }

    @Override
    public Uni<Void> delete(AgentContextKey key) {
        String sql = """
                DELETE FROM agent_context_documents
                WHERE tenant_id = $1 AND workspace_id = $2 AND scope = $3 AND document_path = $4
                """;

        return pgPool.preparedQuery(sql)
                .execute(Tuple.of(key.tenantId().value(), key.workspaceId(), key.scope(), key.path()))
                .replaceWithVoid();
    }

    private AgentContextQuery effectivePageQuery(AgentContextQuery query) {
        Objects.requireNonNull(query, "AgentContextQuery cannot be null");
        int pageSize = listLimit(query, maxListResults);
        if (query.maxResults() != null) {
            return query;
        }
        return new AgentContextQuery(
                query.tenantId(),
                query.workspaceId(),
                query.scope(),
                query.pathPrefix(),
                pageSize,
                query.after());
    }

    private AgentContextQuery continuationProbeQuery(AgentContextQuery query) {
        if (query.maxResults() == Integer.MAX_VALUE) {
            return query;
        }
        return new AgentContextQuery(
                query.tenantId(),
                query.workspaceId(),
                query.scope(),
                query.pathPrefix(),
                query.maxResults() + 1,
                query.after());
    }

    private int continuationProbeLimit() {
        return maxListResults == Integer.MAX_VALUE ? maxListResults : maxListResults + 1;
    }

    private int listLimit(AgentContextQuery query, int maxAllowedResults) {
        if (query.maxResults() == null) {
            return maxListResults;
        }
        if (query.maxResults() > maxAllowedResults) {
            throw new IllegalArgumentException(
                    "Agent context list request exceeds max result limit of " + maxAllowedResults + ": "
                            + query.maxResults());
        }
        return query.maxResults();
    }

    private Optional<Row> firstRow(RowSet<Row> rows) {
        var iterator = rows.iterator();
        return iterator.hasNext() ? Optional.of(iterator.next()) : Optional.empty();
    }

    private AgentContextDocument toDocument(Row row) {
        AgentContextKey key = new AgentContextKey(
                TenantId.of(row.getString("tenant_id")),
                row.getString("workspace_id"),
                row.getString("scope"),
                row.getString("document_path"));
        return new AgentContextDocument(
                key,
                row.getString("content"),
                row.getString("content_type"),
                metadata(row),
                instant(row, "updated_at"));
    }

    private Map<String, String> metadata(Row row) {
        Object value = row.getValue("metadata");
        if (value == null) {
            return Map.of();
        }
        if (value instanceof io.vertx.core.json.JsonObject jsonObject) {
            return objectMapper.convertValue(jsonObject.getMap(), STRING_MAP);
        }
        if (value instanceof Map<?, ?> map) {
            return objectMapper.convertValue(map, STRING_MAP);
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return objectMapper.readValue(text, STRING_MAP);
            } catch (JsonProcessingException error) {
                throw new IllegalArgumentException("Invalid agent context metadata JSON", error);
            }
        }
        return objectMapper.convertValue(value, STRING_MAP);
    }

    private Instant instant(Row row, String column) {
        Object value = row.getValue(column);
        if (value instanceof Instant instant) {
            return instant;
        }
        if (value instanceof OffsetDateTime offsetDateTime) {
            return offsetDateTime.toInstant();
        }
        if (value instanceof java.sql.Timestamp timestamp) {
            return timestamp.toInstant();
        }
        if (value instanceof String text && !text.isBlank()) {
            return Instant.parse(text);
        }
        OffsetDateTime offsetDateTime = row.getOffsetDateTime(column);
        return offsetDateTime != null ? offsetDateTime.toInstant() : Instant.now();
    }

    private String toJson(Map<String, String> metadata) {
        try {
            return objectMapper.writeValueAsString(metadata != null ? metadata : Map.of());
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid agent context metadata", e);
        }
    }

    private void requireContentWithinLimit(String content) {
        long bytes = (content != null ? content : "").getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        if (bytes > maxDocumentBytes) {
            throw new IllegalArgumentException(
                    "Agent context document exceeds max size of " + maxDocumentBytes + " bytes: " + bytes);
        }
    }

    private void requireMetadataWithinLimit(String metadataJson) {
        long bytes = (metadataJson != null ? metadataJson : "{}")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        if (bytes > maxMetadataBytes) {
            throw new IllegalArgumentException(
                    "Agent context metadata exceeds max size of " + maxMetadataBytes + " bytes: " + bytes);
        }
    }
}
