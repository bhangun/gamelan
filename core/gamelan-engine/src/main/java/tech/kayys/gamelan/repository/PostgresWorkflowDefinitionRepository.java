package tech.kayys.gamelan.repository;

import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import tech.kayys.gamelan.engine.repository.WorkflowDefinitionRepository;
import tech.kayys.gamelan.engine.workflow.WorkflowDefinition;
import tech.kayys.gamelan.engine.workflow.WorkflowDefinitionId;
import tech.kayys.gamelan.engine.tenant.TenantId;
import io.vertx.mutiny.sqlclient.Tuple;
import java.time.ZoneOffset;

/**
 * PostgreSQL implementation of definition repository
 */
@ApplicationScoped
@io.quarkus.arc.properties.IfBuildProperty(name = "quarkus.datasource.db-kind", stringValue = "postgresql")
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
                SELECT definition_json, created_at
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
                 created_at, created_by, is_active)
                VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9)
                ON CONFLICT (tenant_id, name, version) DO UPDATE SET
                    definition_json = EXCLUDED.definition_json,
                    updated_at = NOW(),
                    is_active = EXCLUDED.is_active
                RETURNING definition_id
                """;

        try {
            String definitionJson = objectMapper.writeValueAsString(definition);

            return pgPool.preparedQuery(sql)
                    .execute(Tuple.tuple()
                            .addValue(definition.id().value())
                            .addValue(tenantId.value())
                            .addValue(definition.name())
                            .addValue(definition.version())
                            .addValue(definition.description())
                            .addValue(definitionJson)
                            .addValue(definition.metadata().createdAt().atOffset(ZoneOffset.UTC))
                            .addValue(definition.metadata().createdBy())
                            .addValue(true))
                    .map(rows -> definition)
                    .onFailure().invoke(error -> LOG.error("Failed to save definition", error));

        } catch (Exception e) {
            LOG.error("Failed to serialize definition", e);
            return Uni.createFrom().failure(e);
        }
    }

    @Override
    public Uni<List<WorkflowDefinition>> findByTenant(
            TenantId tenantId,
            boolean activeOnly) {

        String sql = activeOnly
                ? "SELECT definition_json, created_at FROM workflow_definitions WHERE tenant_id = $1 AND is_active = true"
                : "SELECT definition_json, created_at FROM workflow_definitions WHERE tenant_id = $1";

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
            // Read the full JSON from definition_json column
            // Vert.x pg client returns JSONB as JsonObject or String depending on
            // version/config
            Object val = row.getValue("definition_json");
            String json;
            if (val instanceof String) {
                json = (String) val;
            } else {
                json = val.toString();
            }

            return objectMapper.readValue(json, WorkflowDefinition.class);
        } catch (Exception e) {
            LOG.error("Failed to deserialize definition JSON", e);
            throw new RuntimeException("Failed to deserialize definition", e);
        }
    }
}