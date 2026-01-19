package tech.kayys.gamelan.registry.persistence;

import io.quarkus.test.junit.QuarkusTestProfile;
import java.util.Map;

public class DatabaseTestProfile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of(
            "quarkus.datasource.db-kind", "postgresql",
            "quarkus.datasource.username", "sa",
            "quarkus.datasource.password", "",
            "quarkus.datasource.reactive.url", "postgresql://localhost:5432/testdb",
            "quarkus.hibernate-orm.database.generation", "drop-and-create",
            "quarkus.flyway.migrate-at-start", "false"
        );
    }
}