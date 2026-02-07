package tech.kayys.gamelan.sdk.client;

import io.vertx.mutiny.core.Vertx;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Remote implementation of the Gamelan Client SDK (REST/gRPC).
 */
public class RemoteGamelanClient implements GamelanClient {

    private final GamelanClientConfig config;
    private final Vertx vertx;
    private final WorkflowRunClient runClient;
    private final WorkflowDefinitionClient definitionClient;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public RemoteGamelanClient(GamelanClientConfig config) {
        this.config = config;
        this.vertx = config.vertx() != null ? config.vertx() : Vertx.vertx();

        if (config.transport() == TransportType.REST) {
            this.runClient = new RestWorkflowRunClient(config, vertx);
            this.definitionClient = new RestWorkflowDefinitionClient(config, vertx);
        } else if (config.transport() == TransportType.GRPC) {
            // Placeholder for gRPC
            throw new UnsupportedOperationException("gRPC transport not implemented yet");
        } else {
            throw new IllegalArgumentException("Unsupported transport for RemoteGamelanClient: " + config.transport());
        }
    }

    @Override
    public GamelanClientConfig config() {
        return config;
    }

    @Override
    public WorkflowRunOperations runs() {
        checkClosed();
        return new WorkflowRunOperations(runClient);
    }

    @Override
    public WorkflowDefinitionOperations workflows() {
        checkClosed();
        return new WorkflowDefinitionOperations(definitionClient);
    }

    private void checkClosed() {
        if (closed.get()) {
            throw new IllegalStateException("RemoteGamelanClient is closed");
        }
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            if (runClient != null) {
                runClient.close();
            }
            if (definitionClient != null) {
                definitionClient.close();
            }
            if (config.vertx() == null && vertx != null) {
                vertx.close();
            }
        }
    }
}
