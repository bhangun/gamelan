package tech.kayys.silat.repository;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import tech.kayys.silat.model.TenantId;
import tech.kayys.silat.model.WorkflowDefinition;
import tech.kayys.silat.model.WorkflowDefinitionId;
import io.vertx.mutiny.sqlclient.Tuple;

/**
 * PostgreSQL implementation of definition repository
 */
@ApplicationScoped
public class PostgresWorkflowDefinitionRepository implements WorkflowDefinitionRepository {

    private static final Logger LOG = LoggerFactory.getLogger(PostgresWorkflowDefinitionRepository.class);

    @Inject
    io.vertx.mutiny.sqlclient.Pool pgPool;

    @Inject
    com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    @Override
    public Uni<WorkflowDefinition> findById(
            WorkflowDefinitionId id,
            TenantId tenantId) {

        String sql = """
                SELECT definition_id, name, version, description, definition_json,
                       created_at, created_by, metadata
                FROM workflow_definitions
                WHERE definition_id = $1 AND tenant_id = $2 AND is_active = true
                """;

        return pgPool.preparedQuery(sql)
                .execute(io.vertx.mutiny.sqlclient.Tuple.of(id.value(), tenantId.value()))
                .map(rows -> {
                    if (!rows.iterator().hasNext()) {
                        return null;
                    }

                    io.vertx.mutiny.sqlclient.Row row = rows.iterator().next();
                    return deserializeDefinition(row);
                })
                .onFailure().invoke(error -> LOG.error("Failed to load definition", error));
    }

    @Override
    public Uni<WorkflowDefinition> save(
            WorkflowDefinition definition,
            TenantId tenantId) {

        String sql = """
                INSERT INTO workflow_definitions
                (definition_id, tenant_id, name, version, description, definition_json,
                 created_at, created_by, is_active, metadata)
                VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10)
                ON CONFLICT (tenant_id, name, version) DO UPDATE SET
                    definition_json = EXCLUDED.definition_json,
                    updated_at = NOW(),
                    is_active = EXCLUDED.is_active
                RETURNING definition_id
                """;

        try {
            String definitionJson = objectMapper.writeValueAsString(
                    serializeDefinition(definition));
            String metadataJson = objectMapper.writeValueAsString(
                    definition.metadata().labels());

            return pgPool.preparedQuery(sql)
                    .execute(Tuple.tuple()
                            .addValue(definition.id().value())
                            .addValue(tenantId.value())
                            .addValue(definition.name())
                            .addValue(definition.version())
                            .addValue(definition.description())
                            .addValue(definitionJson)
                            .addValue(definition.metadata().createdAt())
                            .addValue(definition.metadata().createdBy())
                            .addValue(true)
                            .addValue(metadataJson))
                    .map(rows -> definition)
                    .onFailure().invoke(error -> LOG.error("Failed to save definition", error));

        } catch (Exception e) {
            return Uni.createFrom().failure(e);
        }
    }

    @Override
    public Uni<List<WorkflowDefinition>> findByTenant(
            TenantId tenantId,
            boolean activeOnly) {

        String sql = activeOnly ? "SELECT * FROM workflow_definitions WHERE tenant_id = $1 AND is_active = true"
                : "SELECT * FROM workflow_definitions WHERE tenant_id = $1";

        return pgPool.preparedQuery(sql)
                .execute(io.vertx.mutiny.sqlclient.Tuple.of(tenantId.value()))
                .map(rows -> {
                    List<WorkflowDefinition> definitions = new ArrayList<>();
                    for (io.vertx.mutiny.sqlclient.Row row : rows) {
                        definitions.add(deserializeDefinition(row));
                    }
                    return definitions;
                });
    }

    @Override
    public Uni<Void> delete(WorkflowDefinitionId id, TenantId tenantId) {
        String sql = """
                UPDATE workflow_definitions
                SET is_active = false, updated_at = NOW()
                WHERE definition_id = $1 AND tenant_id = $2
                """;

        return pgPool.preparedQuery(sql)
                .execute(io.vertx.mutiny.sqlclient.Tuple.of(id.value(), tenantId.value()))
                .replaceWithVoid();
    }

    private WorkflowDefinition deserializeDefinition(io.vertx.mutiny.sqlclient.Row row) {
        try {
            // Simplified deserialization - in real implementation,
            // would fully reconstruct from JSON
            return null; // Placeholder
        } catch (Exception e) {
            throw new RuntimeException("Failed to deserialize definition", e);
        }
    }

    private Map<String, Object> serializeDefinition(WorkflowDefinition definition) {
        // Serialize to map for JSON storage
        Map<String, Object> map = new HashMap<>();
        map.put("id", definition.id().value());
        map.put("name", definition.name());
        map.put("version", definition.version());
        map.put("nodes", definition.nodes());
        // ... serialize all fields
        return map;
    }
}