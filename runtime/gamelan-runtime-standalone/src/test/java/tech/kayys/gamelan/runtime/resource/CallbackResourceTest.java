package tech.kayys.gamelan.runtime.resource;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
class CallbackResourceTest {

    @Test
    void testSwaggerUIEndpoint() {
        given()
        .when()
            .get("/q/swagger-ui")
        .then()
            .statusCode(200);
    }

    @Test
    void testCallbackSignalEndpointExists() {
        given()
            .header("Content-Type", "application/json")
            .body("{}")
            .queryParam("token", "test-token")
        .when()
            .post("/api/v1/callbacks/test-run-id/signal")
        .then()
            .statusCode(not(404));
    }
}
