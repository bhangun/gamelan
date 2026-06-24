package tech.kayys.gamelan.runtime.standalone.resource;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.notNullValue;

@QuarkusTest
@Tag("runtime-http")
@EnabledIfSystemProperty(named = "gamelan.runtime.http.tests", matches = "true")
class ExecutorRegistryResourceTest {

    @Test
    void testGetExecutorCount() {
        given()
          .when().get("/api/v1/executors/count")
          .then()
             .statusCode(200);
    }

    @Test
    void testGetAllExecutors() {
        given()
          .when().get("/api/v1/executors")
          .then()
             .statusCode(200)
             .body(notNullValue());
    }

    @Test
    void testGetStatistics() {
        given()
          .when().get("/api/v1/executors/statistics")
          .then()
             .statusCode(200);
    }
}
