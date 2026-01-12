package tech.kayys.silat.dispatcher;

import io.vertx.mutiny.core.Vertx;
import io.vertx.mutiny.ext.web.client.WebClient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;

@ApplicationScoped
public class WebClientProducer {

    @Inject
    Vertx vertx;

    @Produces
    @ApplicationScoped
    public WebClient webClient() {
        return WebClient.create(vertx);
    }
}
