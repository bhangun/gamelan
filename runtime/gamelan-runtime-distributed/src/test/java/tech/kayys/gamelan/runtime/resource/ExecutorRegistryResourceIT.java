package tech.kayys.gamelan.runtime.distributed.resource;

import io.quarkus.test.junit.QuarkusIntegrationTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

@QuarkusIntegrationTest
@Tag("runtime-http")
@EnabledIfSystemProperty(named = "gamelan.runtime.http.tests", matches = "true")
class ExecutorRegistryResourceIT extends ExecutorRegistryResourceTest {
    // Execute the same tests but in packaged mode.
}
