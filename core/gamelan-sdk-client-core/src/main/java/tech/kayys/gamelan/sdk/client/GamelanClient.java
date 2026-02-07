package tech.kayys.gamelan.sdk.client;

/**
 * Main entry point for the Gamelan SDK.
 */
public interface GamelanClient extends AutoCloseable {

    /**
     * @return the client configuration
     */
    GamelanClientConfig config();

    /**
     * Entry point for workflow run operations.
     * 
     * @return operations for managing workflow runs
     */
    WorkflowRunOperations runs();

    /**
     * Entry point for workflow definition operations.
     * 
     * @return operations for managing workflow definitions
     */
    WorkflowDefinitionOperations workflows();

    /**
     * Creates a new builder for {@link GamelanClient}.
     */
    static GamelanClientBuilder builder() {
        return new GamelanClientBuilder();
    }

    /**
     * Creates a client from configuration.
     */
    static GamelanClient create(GamelanClientConfig config) {
        return GamelanClientProvider.findAndCreate(config);
    }

    @Override
    void close();
}
