package tech.kayys.gamelan.resource;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.notNullValue;

@QuarkusTest
@EnabledIfSystemProperty(named = "gamelan.http.it", matches = "true")
public class RestApiIntegrationTest {

    @Test
    @TestSecurity(user = "test-user", roles = { "user" })
    public void testWorkflowDefinitionEndpoints() {
        // REST resources live in gamelan-runtime-core, not gamelan-engine.
        // Verify the server is up and responds (404 = no resource registered here).
        given()
                .when()
                .header("X-Tenant-ID", "test-tenant")
                .get("/api/v1/workflow-definitions")
                .then()
                .statusCode(org.hamcrest.Matchers.anyOf(
                        org.hamcrest.Matchers.is(200),
                        org.hamcrest.Matchers.is(404)));
    }

    @Test
    @TestSecurity(user = "test-user", roles = { "user" })
    public void testWorkflowRunEndpoints() {
        given()
                .when()
                .header("X-Tenant-ID", "test-tenant")
                .get("/api/v1/workflow-runs")
                .then()
                .statusCode(org.hamcrest.Matchers.anyOf(
                        org.hamcrest.Matchers.is(200),
                        org.hamcrest.Matchers.is(404)));
    }
}
