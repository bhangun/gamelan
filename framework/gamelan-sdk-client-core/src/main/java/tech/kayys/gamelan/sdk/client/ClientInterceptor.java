package tech.kayys.gamelan.sdk.client;

import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.ext.web.client.HttpRequest;

/**
 * Interceptor for Gamelan SDK client requests.
 * Primarily used by REST transport to modify outgoing HTTP requests.
 */
@FunctionalInterface
public interface ClientInterceptor {
    /**
     * Intercepts and potentially modifies an outgoing REST request.
     * 
     * @param request the current HTTP request builder
     * @return a Uni that completes when interception is finished
     */
    Uni<Void> intercept(HttpRequest<?> request);
}
