package tech.kayys.gamelan.sdk.client;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import tech.kayys.gamelan.engine.workflow.WorkflowRunManager;
import tech.kayys.gamelan.engine.workflow.WorkflowDefinitionService;

public class GamelanClientTest {

    @Test
    void testBuilderAndConfig() {
        GamelanClient client = GamelanClient.builder()
                .restEndpoint("http://localhost:8080")
                .tenantId("test-tenant")
                .apiKey("test-key")
                .timeout(Duration.ofSeconds(60))
                .header("Custom-Header", "Value")
                .build();

        assertNotNull(client.config());
        assertEquals("http://localhost:8080", client.config().endpoint());
        assertEquals("test-tenant", client.config().tenantId());
        assertEquals("test-key", client.config().apiKey());
        assertEquals(Duration.ofSeconds(60), client.config().timeout());
        assertEquals("Value", client.config().headers().get("Custom-Header"));
        assertEquals(TransportType.REST, client.config().transport());

        client.close();
    }

    @Test
    void testTransportSwitching() {
        // Local
        WorkflowRunManager runManager = mock(WorkflowRunManager.class);
        WorkflowDefinitionService defService = mock(WorkflowDefinitionService.class);
        GamelanClient localClient = GamelanClient.builder()
                .tenantId("test-tenant")
                .local(runManager, defService, "test-tenant")
                .build();
        assertEquals(TransportType.LOCAL, localClient.config().transport());
        localClient.close();

        // gRPC
        GamelanClient grpcClient = GamelanClient.builder()
                .tenantId("test-tenant")
                .grpcEndpoint("localhost", 9090)
                .build();
        assertEquals(TransportType.GRPC, grpcClient.config().transport());
        grpcClient.close();

        // REST
        GamelanClient restClient = GamelanClient.builder()
                .tenantId("test-tenant")
                .restEndpoint("http://localhost:8080")
                .build();
        assertEquals(TransportType.REST, restClient.config().transport());
        restClient.close();
    }

    @Test
    void testCloseAndState() {
        GamelanClient client = GamelanClient.builder()
                .restEndpoint("http://localhost:8080")
                .tenantId("test-tenant")
                .build();

        // Should work
        assertNotNull(client.runs());
        assertNotNull(client.workflows());

        client.close();

        // Should throw IllegalStateException
        assertThrows(IllegalStateException.class, client::runs);
        assertThrows(IllegalStateException.class, client::workflows);

        // Double close should be safe
        assertDoesNotThrow(client::close);
    }
}
