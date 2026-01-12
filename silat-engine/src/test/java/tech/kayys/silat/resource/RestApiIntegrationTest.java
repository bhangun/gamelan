package tech.kayys.silat.resource;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.notNullValue;

@QuarkusTest
public class RestApiIntegrationTest {

    @Test
    @TestSecurity(user = "test-user", roles = { "user" })
    public void testWorkflowDefinitionEndpoints() {
        given()
                .when()
                .header("X-Tenant-ID", "test-tenant")
                .get("/definitions")
                .then()
                .statusCode(200)
                .body(notNullValue());
    }

    @Test
    @TestSecurity(user = "test-user", roles = { "user" })
    public void testWorkflowRunEndpoints() {
        given()
                .when()
                .header("X-Tenant-ID", "test-tenant")
                .get("/runs")
                .then()
                .statusCode(200)
                .body(notNullValue());
    }
}
