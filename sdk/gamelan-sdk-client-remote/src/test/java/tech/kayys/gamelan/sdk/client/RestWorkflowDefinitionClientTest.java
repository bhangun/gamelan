package tech.kayys.gamelan.sdk.client;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import io.smallrye.mutiny.helpers.test.UniAssertSubscriber;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tech.kayys.gamelan.engine.workflow.WorkflowDefinition;
import tech.kayys.gamelan.engine.workflow.WorkflowDefinitionId;
import tech.kayys.gamelan.engine.tenant.TenantId;
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
        private GamelanClientConfig config;

        private static final com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper()
                        .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
                        .registerModule(new com.fasterxml.jackson.module.paramnames.ParameterNamesModule())
                        .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                                        false);

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

                config = GamelanClientConfig.builder()
                                .endpoint("http://localhost:" + wireMockServer.port())
                                .tenantId("test-tenant")
                                .apiKey("test-api-key")
                                .build();
                client = new RestWorkflowDefinitionClient(config, io.vertx.mutiny.core.Vertx.vertx());
        }

        @AfterEach
        void tearDown() {
                client.close();
                wireMockServer.stop();
        }

        @Test
        void testCreateWorkflow() {
                WorkflowDefinition definition = new WorkflowDefinition(
                                new WorkflowDefinitionId("def-123"), TenantId.of("test-tenant"), "Test Workflow",
                                "1.0.0", "Description",
                                tech.kayys.gamelan.engine.workflow.WorkflowMode.FLOW,
                                Collections.emptyList(), Collections.emptyMap(), Collections.emptyMap(),
                                null, null, null);

                stubFor(post(urlEqualTo("/api/v1/workflow-definitions"))
                                .withHeader("X-Tenant-ID", equalTo("test-tenant"))
                                .withHeader("Authorization", equalTo("Bearer test-api-key"))
                                .willReturn(aResponse()
                                                .withStatus(201)
                                                .withHeader("Content-Type", "application/json")
                                                .withBody(toJson(definition))));

                WorkflowDefinition created = client.createWorkflow(definition)
                                .subscribe().withSubscriber(UniAssertSubscriber.create())
                                .awaitItem()
                                .getItem();

                assertNotNull(created);
                assertEquals("Test Workflow", created.name());
        }

        @Test
        void testGetWorkflow() {
                WorkflowDefinition definition = new WorkflowDefinition(
                                new WorkflowDefinitionId("def-123"), TenantId.of("test-tenant"), "Test Workflow",
                                "1.0.0", "Description",
                                tech.kayys.gamelan.engine.workflow.WorkflowMode.FLOW,
                                Collections.emptyList(), Collections.emptyMap(), Collections.emptyMap(),
                                null, null, null);

                stubFor(get(urlEqualTo("/api/v1/workflow-definitions/def-123"))
                                .withHeader("X-Tenant-ID", equalTo("test-tenant"))
                                .willReturn(aResponse()
                                                .withStatus(200)
                                                .withHeader("Content-Type", "application/json")
                                                .withBody(toJson(definition))));

                WorkflowDefinition result = client.getWorkflow("def-123")
                                .subscribe().withSubscriber(UniAssertSubscriber.create())
                                .awaitItem()
                                .getItem();

                assertNotNull(result);
                assertEquals("Test Workflow", result.name());
        }

        @Test
        void testDeleteWorkflow() {
                stubFor(delete(urlEqualTo("/api/v1/workflow-definitions/def-123"))
                                .withHeader("X-Tenant-ID", equalTo("test-tenant"))
                                .willReturn(aResponse()
                                                .withStatus(204)));

                client.deleteWorkflow("def-123")
                                .subscribe().withSubscriber(UniAssertSubscriber.create())
                                .awaitItem()
                                .assertCompleted();
        }

        private String toJson(Object obj) {
                try {
                        return mapper.writeValueAsString(obj);
                } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                        throw new RuntimeException(e);
                }
        }
}
