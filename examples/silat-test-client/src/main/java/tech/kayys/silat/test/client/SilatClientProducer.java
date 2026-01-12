package tech.kayys.silat.test.client;

import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import tech.kayys.silat.sdk.client.SilatClient;
import tech.kayys.silat.sdk.client.SilatClientConfig;

@ApplicationScoped
public class SilatClientProducer {

    @Produces
    @ApplicationScoped
    @DefaultBean
    public SilatClient silatClient() {
        return SilatClient.builder()
                .restEndpoint("http://localhost:8081")
                .tenantId("default-tenant")
                .build();
    }
}
