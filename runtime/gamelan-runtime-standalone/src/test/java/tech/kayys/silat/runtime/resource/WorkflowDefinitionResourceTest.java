package tech.kayys.gamelan.runtime.resource;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class WorkflowDefinitionResourceTest {

    @Test
    @Order(1)
    void testCreateWorkflowDefinition() {
        String requestBody = """
            {
                "name": "test-workflow",
                "version": "1.0.0",
                "definition": {
                    "nodes": [
                        {
                            "id": "start",
                            "type": "START",
                            "next": ["end"]
                        },
                        {
                            "id": "end",
                            "type": "END"
                        }
                    ]
                }
            }
            """;

        given()
            .header("Content-Type", "application/json")
            .body(requestBody)
        .when()
            .post("/api/v1/workflow-definitions")
        .then()
            .statusCode(200)
            .body("id", notNullValue())
            .body("name", equalTo("test-workflow"))
            .body("version", equalTo("1.0.0"));
    }

    @Test
    @Order(2)
    void testGetWorkflowDefinition() {
        given()
        .when()
            .get("/api/v1/workflow-definitions/test-workflow")
        .then()
            .statusCode(200)
            .body("name", equalTo("test-workflow"));
    }

    @Test
    @Order(3)
    void testListWorkflowDefinitions() {
        given()
        .when()
            .get("/api/v1/workflow-definitions")
        .then()
            .statusCode(200)
            .body("definitions", hasSize(greaterThanOrEqualTo(1)));
    }

    @Test
    @Order(4)
    void testUpdateWorkflowDefinition() {
        String requestBody = """
            {
                "name": "test-workflow",
                "version": "1.0.1",
                "definition": {
                    "nodes": [
                        {
                            "id": "start",
                            "type": "START",
                            "next": ["end"]
                        },
                        {
                            "id": "end",
                            "type": "END"
                        }
                    ]
                }
            }
            """;

        given()
            .header("Content-Type", "application/json")
            .body(requestBody)
        .when()
            .put("/api/v1/workflow-definitions/test-workflow")
        .then()
            .statusCode(200)
            .body("version", equalTo("1.0.1"));
    }

    @Test
    @Order(5)
    void testDeleteWorkflowDefinition() {
        given()
        .when()
            .delete("/api/v1/workflow-definitions/test-workflow")
        .then()
            .statusCode(204);
    }

    @Test
    @Order(6)
    void testGetNonExistentWorkflowDefinition() {
        given()
        .when()
            .get("/api/v1/workflow-definitions/non-existent-workflow")
        .then()
            .statusCode(404);
    }
}