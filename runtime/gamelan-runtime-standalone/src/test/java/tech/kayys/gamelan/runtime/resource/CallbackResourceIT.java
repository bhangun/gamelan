package tech.kayys.gamelan.runtime.standalone.resource;

import io.quarkus.test.junit.QuarkusIntegrationTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

@QuarkusIntegrationTest
@Tag("runtime-http")
@EnabledIfSystemProperty(named = "gamelan.runtime.http.tests", matches = "true")
class CallbackResourceIT extends CallbackResourceTest {

    // Execute the same tests as the unit test but in a full integration environment
}
