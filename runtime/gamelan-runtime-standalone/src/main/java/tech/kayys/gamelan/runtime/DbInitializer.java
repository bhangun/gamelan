package tech.kayys.gamelan.runtime;

import io.quarkus.runtime.StartupEvent;
import io.vertx.mutiny.pgclient.PgPool;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Automatically initializes the database schema on startup.
 * Useful when not using Flyway or Liquibase for non-entity tables.
 */
@ApplicationScoped
public class DbInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger(DbInitializer.class);

    @Inject
    PgPool client;

    void onStart(@Observes StartupEvent ev) {
        LOGGER.info("Initializing database schema for workflow_definitions...");

        String sql = """
                CREATE TABLE IF NOT EXISTS workflow_definitions (
                    definition_id VARCHAR(128) PRIMARY KEY,
                    tenant_id VARCHAR(64) NOT NULL,
                    name VARCHAR(255) NOT NULL,
                    version VARCHAR(32) NOT NULL,
                    description TEXT,
                    definition_json JSONB NOT NULL,
                    is_active BOOLEAN DEFAULT true,
                    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
                    created_by VARCHAR(128),
                    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
                    updated_by VARCHAR(128),
                    metadata JSONB,
                    CONSTRAINT uk_workflow_def_tenant_name_version UNIQUE (tenant_id, name, version)
                );
                """;

        client.query(sql).execute()
                .subscribe().with(
                        result -> LOGGER.info("Database schema 'workflow_definitions' initialized successfully"),
                        error -> LOGGER.error("Failed to initialize database schema", error));
    }
}
