package tech.kayys.gamelan.runtime.resource;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
class PluginResourceTest {

    @Test
    void testGetAllPlugins() {
        given()
        .when()
            .get("/api/plugins")
        .then()
            .statusCode(200);
    }

    @Test
    void testGetNonExistentPlugin() {
        given()
        .when()
            .get("/api/plugins/non-existent.jar")
        .then()
            .statusCode(anyOf(is(404), is(500)));
    }

    @Test
    void testRefreshPlugins() {
        given()
            .header("Content-Type", "application/json")
        .when()
            .post("/api/plugins/refresh")
        .then()
            .statusCode(200);
    }
}
