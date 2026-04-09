package tech.kayys.gamelan.registry.persistence;

import java.util.Map;

import io.quarkus.test.junit.QuarkusTestProfile;

/**
 * Test profile that activates the database persistence type at build time,
 * so @IfBuildProperty on DatabaseExecutorRepository is satisfied.
 */
public class DatabaseTestProfile implements QuarkusTestProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of("gamelan.registry.persistence.type", "database");
    }
}
