package tech.kayys.silat.sdk.client;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;

public class SilatClientTest {

    @Test
    void testBuilderAndConfig() {
        SilatClient client = SilatClient.builder()
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
    void testCloseAndState() {
        SilatClient client = SilatClient.builder()
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
