package tech.kayys.gamelan.test.client;

import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import tech.kayys.gamelan.sdk.client.GamelanClient;

@ApplicationScoped
public class GamelanClientProducer {

    @ConfigProperty(name = "engine.rest.url", defaultValue = "http://localhost:3100")
    String engineUrl;

    @ConfigProperty(name = "gamelan.tenant.id", defaultValue = "default")
    String tenantId;

    @Produces
    @ApplicationScoped
    @DefaultBean
    public GamelanClient gamelanClient() {
        return GamelanClient.builder()
                .restEndpoint(this.engineUrl)
                .tenantId(this.tenantId)
                .build();
    }
}
