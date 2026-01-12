package tech.kayys.silat.sdk.client;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import io.smallrye.mutiny.helpers.test.UniAssertSubscriber;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tech.kayys.silat.model.WorkflowDefinition;
import tech.kayys.silat.model.WorkflowDefinitionId;
import tech.kayys.silat.model.TenantId;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.databind.DeserializationFeature;
import io.vertx.core.json.jackson.DatabindCodec;
import org.junit.jupiter.api.BeforeAll;
import java.util.Collections;
import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class RestWorkflowDefinitionClientTest {

        private WireMockServer wireMockServer;
        private RestWorkflowDefinitionClient client;
        private SilatClientConfig config;

        @BeforeAll
        static void beforeAll() {
                DatabindCodec.mapper().registerModule(new JavaTimeModule());
                DatabindCodec.mapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
                DatabindCodec.prettyMapper().registerModule(new JavaTimeModule());
                DatabindCodec.prettyMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        }

        @BeforeEach
        void setUp() {
                wireMockServer = new WireMockServer(wireMockConfig().dynamicPort());
                wireMockServer.start();
                WireMock.configureFor("localhost", wireMockServer.port());

                config = SilatClientConfig.builder()
                                .endpoint("http://localhost:" + wireMockServer.port())
                                .tenantId("test-tenant")
                                .apiKey("test-api-key")
                                .build();
                client = new RestWorkflowDefinitionClient(config);
        }

        @AfterEach
        void tearDown() {
                client.close();
                wireMockServer.stop();
        }

        @Test
        void testCreateDefinition() {
                WorkflowDefinition definition = new WorkflowDefinition(
                                new WorkflowDefinitionId("def-123"), TenantId.of("test-tenant"), "Test Workflow",
                                "1.0.0", "Description",
                                Collections.emptyList(), Collections.emptyMap(), Collections.emptyMap(),
                                null, null, null);

                stubFor(post(urlEqualTo("/workflow-definitions"))
                                .withHeader("Tenant-ID", equalTo("test-tenant"))
                                .withHeader("Authorization", equalTo("Bearer test-api-key"))
                                .willReturn(aResponse()
                                                .withStatus(201)
                                                .withHeader("Content-Type", "application/json")
                                                .withBody(JsonObject.mapFrom(definition).encode())));

                WorkflowDefinition created = client.createDefinition(definition)
                                .subscribe().withSubscriber(UniAssertSubscriber.create())
                                .awaitItem()
                                .getItem();

                assertNotNull(created);
                assertEquals("Test Workflow", created.name());
        }

        @Test
        void testGetDefinition() {
                WorkflowDefinition definition = new WorkflowDefinition(
                                new WorkflowDefinitionId("def-123"), TenantId.of("test-tenant"), "Test Workflow",
                                "1.0.0", "Description",
                                Collections.emptyList(), Collections.emptyMap(), Collections.emptyMap(),
                                null, null, null);

                stubFor(get(urlEqualTo("/workflow-definitions/def-123"))
                                .withHeader("Tenant-ID", equalTo("test-tenant"))
                                .willReturn(aResponse()
                                                .withStatus(200)
                                                .withHeader("Content-Type", "application/json")
                                                .withBody(JsonObject.mapFrom(definition).encode())));

                WorkflowDefinition result = client.getDefinition("def-123")
                                .subscribe().withSubscriber(UniAssertSubscriber.create())
                                .awaitItem()
                                .getItem();

                assertNotNull(result);
                assertEquals("Test Workflow", result.name());
        }

        @Test
        void testDeleteDefinition() {
                stubFor(delete(urlEqualTo("/workflow-definitions/def-123"))
                                .withHeader("Tenant-ID", equalTo("test-tenant"))
                                .willReturn(aResponse()
                                                .withStatus(204)));

                client.deleteDefinition("def-123")
                                .subscribe().withSubscriber(UniAssertSubscriber.create())
                                .awaitItem()
                                .assertCompleted();
        }
}
