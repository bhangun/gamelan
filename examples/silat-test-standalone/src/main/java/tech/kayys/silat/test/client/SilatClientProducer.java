package tech.kayys.silat.test.client;

import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import tech.kayys.silat.sdk.client.SilatClient;

@ApplicationScoped
public class SilatClientProducer {

    @ConfigProperty(name = "engine.rest.url", defaultValue = "http://localhost:3100")
    String engineUrl;

    @ConfigProperty(name = "silat.tenant.id", defaultValue = "default")
    String tenantId;

    @Produces
    @ApplicationScoped
    @DefaultBean
    public SilatClient silatClient() {
        return SilatClient.builder()
                .restEndpoint(this.engineUrl)
                .tenantId(this.tenantId)
                .build();
    }
}
