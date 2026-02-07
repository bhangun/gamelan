package tech.kayys.gamelan.sdk.client;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Local implementation of the Gamelan Client SDK.
 */
public class LocalGamelanClient implements tech.kayys.gamelan.sdk.client.GamelanClient {

    private final GamelanClientConfig config;
    private final WorkflowRunClient runClient;
    private final WorkflowDefinitionClient definitionClient;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public LocalGamelanClient(GamelanClientConfig config) {
        this.config = config;
        this.runClient = new LocalWorkflowRunClient(config.runManager(), config.tenantId());
        this.definitionClient = new LocalWorkflowDefinitionClient(config.definitionService(), config.tenantId());
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
            throw new IllegalStateException("LocalGamelanClient is closed");
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
        }
    }
}
