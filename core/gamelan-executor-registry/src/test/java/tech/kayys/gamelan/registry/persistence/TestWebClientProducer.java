package tech.kayys.gamelan.registry.persistence;

import io.quarkus.arc.DefaultBean;
import io.quarkus.test.Mock;
import io.vertx.mutiny.core.Vertx;
import io.vertx.mutiny.ext.web.client.WebClient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;

/**
 * Provides a WebClient CDI bean for tests.
 * WebClient has no Quarkus-managed producer — must be provided explicitly.
 */
@ApplicationScoped
public class TestWebClientProducer {

    @Inject
    Vertx vertx;

    @Produces
    @DefaultBean
    @ApplicationScoped
    WebClient webClient() {
        return WebClient.create(vertx);
    }
}
