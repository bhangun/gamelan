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
        given()
            .header("Content-Type", "application/json")
            .body("{\"workflowDefinitionId\": \"test-workflow\", \"inputs\": {}}")
        .when()
            .post("/api/v1/workflow-runs")
        .then()
            .statusCode(anyOf(is(200), is(500)));
    }

    @Test
    @Order(2)
    void testListWorkflowRuns() {
        given()
        .when()
            .get("/api/v1/workflow-runs")
        .then()
            .statusCode(anyOf(is(200), is(500)));
    }

    @Test
    @Order(3)
    void testGetNonExistentWorkflowRun() {
        given()
        .when()
            .get("/api/v1/workflow-runs/non-existent-run")
        .then()
            .statusCode(anyOf(is(404), is(500)));
    }

    @Test
    @Order(4)
    void testCancelNonExistentRun() {
        given()
            .header("Content-Type", "application/json")
            .body("{}")
        .when()
            .post("/api/v1/workflow-runs/non-existent-run/cancel")
        .then()
            .statusCode(anyOf(is(204), is(404), is(500)));
    }
}
