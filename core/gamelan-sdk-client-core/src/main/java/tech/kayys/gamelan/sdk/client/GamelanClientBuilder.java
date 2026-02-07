package tech.kayys.gamelan.sdk.client;

import tech.kayys.gamelan.engine.workflow.WorkflowDefinitionService;
import tech.kayys.gamelan.engine.workflow.WorkflowRunManager;
import java.time.Duration;

/**
 * Fluent builder for {@link GamelanClient}.
 */
public class GamelanClientBuilder {
    private final GamelanClientConfig.Builder configBuilder = GamelanClientConfig.builder();

    public GamelanClientBuilder endpoint(String endpoint) {
        configBuilder.endpoint(endpoint);
        return this;
    }

    public GamelanClientBuilder restEndpoint(String endpoint) {
        configBuilder.endpoint(endpoint).rest();
        return this;
    }

    public GamelanClientBuilder grpcEndpoint(String endpoint) {
        configBuilder.endpoint(endpoint).grpc();
        return this;
    }

    public GamelanClientBuilder local(WorkflowRunManager runManager, WorkflowDefinitionService definitionService,
            String tenantId) {
        configBuilder.endpoint("local").local()
                .runManager(runManager)
                .definitionService(definitionService)
                .tenantId(tenantId);
        return this;
    }

    public GamelanClientBuilder tenantId(String tenantId) {
        configBuilder.tenantId(tenantId);
        return this;
    }

    public GamelanClientBuilder apiKey(String apiKey) {
        configBuilder.apiKey(apiKey);
        return this;
    }

    public GamelanClientBuilder timeout(Duration timeout) {
        configBuilder.timeout(timeout);
        return this;
    }

    public GamelanClientBuilder header(String key, String value) {
        configBuilder.header(key, value);
        return this;
    }

    public GamelanClientBuilder vertx(io.vertx.mutiny.core.Vertx vertx) {
        configBuilder.vertx(vertx);
        return this;
    }

    public GamelanClientBuilder interceptor(ClientInterceptor interceptor) {
        configBuilder.interceptor(interceptor);
        return this;
    }

    public GamelanClient build() {
        return GamelanClient.create(configBuilder.build());
    }
}
