package tech.kayys.gamelan.runtime.resource;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PluginResourceTest {

    @Test
    @Order(1)
    void testUploadPlugin() throws IOException {
        // Create a dummy JAR file for testing
        Path tempDir = Paths.get("target", "test-plugins");
        Files.createDirectories(tempDir);
        
        File dummyJar = tempDir.resolve("test-plugin.jar").toFile();
        try (FileOutputStream fos = new FileOutputStream(dummyJar)) {
            // Write minimal JAR content (just for testing)
            byte[] dummyContent = "dummy-jar-content".getBytes();
            fos.write(dummyContent);
        }

        given()
            .multiPart("uploadedInputStream", dummyJar)
            .multiPart("filename", "test-plugin.jar")
        .when()
            .post("/api/plugins/upload")
        .then()
            .statusCode(200)
            .body("message", containsString("Plugin uploaded and loaded successfully"));
    }

    @Test
    @Order(2)
    void testGetAllPlugins() {
        given()
        .when()
            .get("/api/plugins")
        .then()
            .statusCode(200)
            .body("plugins", hasSize(greaterThanOrEqualTo(0)));
    }

    @Test
    @Order(3)
    void testGetPlugin() {
        given()
        .when()
            .get("/api/plugins/test-plugin.jar")
        .then()
            .statusCode(200)
            .body("fileName", equalTo("test-plugin.jar"));
    }

    @Test
    @Order(4)
    void testEnablePlugin() {
        given()
        .when()
            .put("/api/plugins/test-plugin.jar/enable")
        .then()
            .statusCode(200)
            .body("message", containsString("Plugin enabled successfully"));
    }

    @Test
    @Order(5)
    void testDisablePlugin() {
        given()
        .when()
            .put("/api/plugins/test-plugin.jar/disable")
        .then()
            .statusCode(200)
            .body("message", containsString("Plugin disabled successfully"));
    }

    @Test
    @Order(6)
    void testGetPluginConfig() {
        given()
        .when()
            .get("/api/plugins/test-plugin.jar/config")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(7)
    void testUpdatePluginConfig() {
        String configJson = """
            {
                "config": {
                    "test.property": "test.value"
                }
            }
            """;

        given()
            .header("Content-Type", "application/json")
            .body(configJson)
        .when()
            .post("/api/plugins/test-plugin.jar/config")
        .then()
            .statusCode(200)
            .body("message", containsString("Plugin configuration updated successfully"));
    }

    @Test
    @Order(8)
    void testUpdatePluginConfigProperty() {
        given()
            .header("Content-Type", "text/plain")
            .body("updated-value")
        .when()
            .put("/api/plugins/test-plugin.jar/config/test-key")
        .then()
            .statusCode(200)
            .body("message", containsString("Plugin configuration property updated"));
    }

    @Test
    @Order(9)
    void testRemovePluginConfigProperty() {
        given()
        .when()
            .delete("/api/plugins/test-plugin.jar/config/test-key")
        .then()
            .statusCode(200)
            .body("message", containsString("Plugin configuration property removed"));
    }

    @Test
    @Order(10)
    void testRefreshPlugins() {
        given()
        .when()
            .post("/api/plugins/refresh")
        .then()
            .statusCode(200)
            .body("message", containsString("Plugin refresh completed"));
    }

    @Test
    @Order(11)
    void testDeletePlugin() {
        given()
        .when()
            .delete("/api/plugins/test-plugin.jar")
        .then()
            .statusCode(200)
            .body("message", containsString("Plugin deleted successfully"));
    }
}