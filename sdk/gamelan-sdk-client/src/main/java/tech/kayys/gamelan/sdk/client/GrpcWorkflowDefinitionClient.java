package tech.kayys.gamelan.sdk.client;

import io.smallrye.mutiny.Uni;
import tech.kayys.gamelan.engine.workflow.WorkflowDefinition;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * gRPC-based workflow definition client
 */
class GrpcWorkflowDefinitionClient implements WorkflowDefinitionClient {

    private final GamelanClientConfig config;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    GrpcWorkflowDefinitionClient(GamelanClientConfig config) {
        this.config = config;
    }

    /**
     * Get the client configuration
     */
    public GamelanClientConfig config() {
        return config;
    }

    // Implement using gRPC stubs...

    @Override
    public Uni<WorkflowDefinition> createDefinition(WorkflowDefinition request) {
        checkClosed();
        return null;
    }

    @Override
    public Uni<WorkflowDefinition> getDefinition(String definitionId) {
        checkClosed();
        return null;
    }

    @Override
    public Uni<List<WorkflowDefinition>> listDefinitions(boolean activeOnly) {
        checkClosed();
        return null;
    }

    @Override
    public Uni<Void> deleteDefinition(String definitionId) {
        checkClosed();
        return null;
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            // Close gRPC resources if needed
        }
    }

    private void checkClosed() {
        if (closed.get()) {
            throw new IllegalStateException("Client is closed");
        }
    }
}
