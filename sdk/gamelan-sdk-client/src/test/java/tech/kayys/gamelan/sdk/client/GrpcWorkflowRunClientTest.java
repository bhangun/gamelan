package tech.kayys.gamelan.sdk.client;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;

class GrpcWorkflowRunClientTest {

    private GrpcWorkflowRunClient client;

    @BeforeEach
    void setUp() {
        GamelanClientConfig config = GamelanClientConfig.builder()
                .endpoint("localhost:9090")
                .grpc()
                .tenantId("test-tenant")
                .build();
        client = new GrpcWorkflowRunClient(config);
    }

    @Test
    void testUnimplementedMethods() {
        assertThrows(UnsupportedOperationException.class, () -> client.getRun("any").await().indefinitely());
        assertThrows(UnsupportedOperationException.class, () -> client.startRun("any").await().indefinitely());
        assertThrows(UnsupportedOperationException.class,
                () -> client.resumeRun("any", Map.of(), null).await().indefinitely());
    }

    @Test
    void testCloseState() {
        client.close();
        assertThrows(IllegalStateException.class, () -> client.getRun("any"));
    }
}
