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
class WorkflowRunResourceTest {

    @Test
    @Order(1)
    void testCreateWorkflowRun() {
        String requestBody = """
            {
                "workflowDefinitionId": "test-workflow",
                "inputs": {
                    "input1": "value1",
                    "input2": "value2"
                },
                "tenantId": "default-tenant"
            }
            """;

        given()
            .header("Content-Type", "application/json")
            .body(requestBody)
        .when()
            .post("/api/v1/workflow-runs")
        .then()
            .statusCode(200)
            .body("id", notNullValue())
            .body("status", equalTo("CREATED"));
    }

    @Test
    @Order(2)
    void testGetWorkflowRun() {
        given()
        .when()
            .get("/api/v1/workflow-runs/1")
        .then()
            .statusCode(200)
            .body("id", notNullValue());
    }

    @Test
    @Order(3)
    void testListWorkflowRuns() {
        given()
        .when()
            .get("/api/v1/workflow-runs")
        .then()
            .statusCode(200)
            .body("runs", hasSize(greaterThanOrEqualTo(0)));
    }

    @Test
    @Order(4)
    void testGetWorkflowRunHistory() {
        given()
        .when()
            .get("/api/v1/workflow-runs/1/history")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(5)
    void testCancelWorkflowRun() {
        given()
        .when()
            .post("/api/v1/workflow-runs/1/cancel")
        .then()
            .statusCode(200)
            .body("status", equalTo("CANCELLED"));
    }

    @Test
    @Order(6)
    void testGetNonExistentWorkflowRun() {
        given()
        .when()
            .get("/api/v1/workflow-runs/999999")
        .then()
            .statusCode(404);
    }
}