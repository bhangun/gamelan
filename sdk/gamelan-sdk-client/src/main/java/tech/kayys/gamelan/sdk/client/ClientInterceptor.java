package tech.kayys.gamelan.sdk.client;

import io.vertx.mutiny.core.buffer.Buffer;
import io.vertx.mutiny.ext.web.client.HttpRequest;

/**
 * Interceptor for Gamelan client requests.
 * Allows modifying the request before it is sent (e.g., adding tracing headers,
 * logging, or implementing retry logic).
 */
public interface ClientInterceptor {
    /**
     * Applies the interceptor logic to the given request.
     * 
     * @param request the request to intercept
     * @return the modified (or original) request
     */
    HttpRequest<Buffer> apply(HttpRequest<Buffer> request);
}