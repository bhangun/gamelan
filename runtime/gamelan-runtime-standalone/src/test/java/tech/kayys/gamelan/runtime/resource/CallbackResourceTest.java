package tech.kayys.gamelan.runtime.standalone.resource;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
@Tag("runtime-http")
@EnabledIfSystemProperty(named = "gamelan.runtime.http.tests", matches = "true")
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
            .header("X-Gamelan-Callback-Token", "test-token")
            .body("""
                    {
                      "signalType": "test_signal",
                      "targetNodeId": "test-node",
                      "payload": {}
                    }
                    """)
        .when()
            .post("/api/v1/callbacks/test-run-id/signal")
        .then()
            .statusCode(not(404));
    }
}
