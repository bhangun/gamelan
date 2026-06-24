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
