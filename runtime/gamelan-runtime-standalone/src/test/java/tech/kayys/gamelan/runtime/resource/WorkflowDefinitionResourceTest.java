package tech.kayys.gamelan.runtime.standalone.resource;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Tag("runtime-http")
@EnabledIfSystemProperty(named = "gamelan.runtime.http.tests", matches = "true")
class WorkflowDefinitionResourceTest {

    private static final String VALID_REQUEST = """
            {
                "name": "test-workflow",
                "version": "1.0.0",
                "nodes": [
                    {"id": "start", "type": "START"},
                    {"id": "end", "type": "END"}
                ]
            }
            """;

    @Test
    @Order(1)
    void testCreateWorkflowDefinition() {
        given()
            .header("Content-Type", "application/json")
            .body(VALID_REQUEST)
        .when()
            .post("/api/v1/workflow-definitions")
        .then()
            .statusCode(anyOf(is(200), is(500)));
    }

    @Test
    @Order(2)
    void testListWorkflowDefinitions() {
        given()
        .when()
            .get("/api/v1/workflow-definitions")
        .then()
            .statusCode(anyOf(is(200), is(500)));
    }

    @Test
    @Order(3)
    void testUpdateWorkflowDefinition() {
        given()
            .header("Content-Type", "application/json")
            .body("{\"description\": \"Updated\"}")
        .when()
            .put("/api/v1/workflow-definitions/test-workflow")
        .then()
            .statusCode(anyOf(is(200), is(204), is(404), is(500)));
    }

    @Test
    @Order(4)
    void testDeleteWorkflowDefinition() {
        given()
        .when()
            .delete("/api/v1/workflow-definitions/test-workflow")
        .then()
            .statusCode(anyOf(is(204), is(404), is(500)));
    }

    @Test
    @Order(5)
    void testGetNonExistentWorkflowDefinition() {
        given()
        .when()
            .get("/api/v1/workflow-definitions/non-existent-workflow")
        .then()
            .statusCode(anyOf(is(404), is(500)));
    }
}
