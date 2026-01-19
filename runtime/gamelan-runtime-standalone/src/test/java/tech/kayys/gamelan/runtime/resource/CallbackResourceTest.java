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
class CallbackResourceTest {

    @Test
    @Order(1)
    void testHandleCallback() {
        String requestBody = """
            {
                "taskId": "test-task-id",
                "runId": "test-run-id",
                "nodeId": "test-node-id",
                "result": {
                    "status": "COMPLETED",
                    "outputs": {
                        "result": "success"
                    }
                }
            }
            """;

        given()
            .header("Content-Type", "application/json")
            .body(requestBody)
        .when()
            .post("/api/v1/callbacks")
        .then()
            .statusCode(200)
            .body("message", equalTo("Callback processed successfully"));
    }

    @Test
    @Order(2)
    void testHandleCallbackWithInvalidData() {
        String requestBody = """
            {
                "taskId": "",
                "invalidField": "shouldNotMatter"
            }
            """;

        given()
            .header("Content-Type", "application/json")
            .body(requestBody)
        .when()
            .post("/api/v1/callbacks")
        .then()
            .statusCode(400);
    }

    @Test
    @Order(3)
    void testHealthCheck() {
        given()
        .when()
            .get("/q/health")
        .then()
            .statusCode(200)
            .body("status", equalTo("UP"));
    }

    @Test
    @Order(4)
    void testMetricsEndpoint() {
        given()
        .when()
            .get("/q/metrics")
        .then()
            .statusCode(200)
            .contentType("text/plain");
    }

    @Test
    @Order(5)
    void testSwaggerUIEndpoint() {
        given()
        .when()
            .get("/q/swagger-ui")
        .then()
            .statusCode(200)
            .contentType("text/html");
    }
}