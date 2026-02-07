package tech.kayys.gamelan.sdk.client;

import io.smallrye.mutiny.Uni;
import tech.kayys.gamelan.engine.run.RunResponse;
import tech.kayys.gamelan.engine.run.CreateRunRequest;
import tech.kayys.gamelan.engine.execution.ExecutionHistory;

import java.util.Map;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * gRPC-based implementation of {@link WorkflowRunClient}.
 * Currently a stub as gRPC transport is not yet implemented.
 */
public class GrpcWorkflowRunClient implements WorkflowRunClient {

    private final GamelanClientConfig config;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    /**
     * Initializes the gRPC client with provided configuration.
     * 
     * @param config the client configuration
     */
    GrpcWorkflowRunClient(GamelanClientConfig config) {
        this.config = config;
    }

    /**
     * @return the client configuration
     */
    public GamelanClientConfig config() {
        return config;
    }

    @Override
    public Uni<RunResponse> createRun(CreateRunRequest request) {
        checkClosed();
        return Uni.createFrom().failure(new UnsupportedOperationException("gRPC transport not implemented yet"));
    }

    @Override
    public Uni<RunResponse> getRun(String runId) {
        checkClosed();
        return Uni.createFrom().failure(new UnsupportedOperationException("gRPC transport not implemented yet"));
    }

    @Override
    public Uni<RunResponse> startRun(String runId) {
        checkClosed();
        return Uni.createFrom().failure(new UnsupportedOperationException("gRPC transport not implemented yet"));
    }

    @Override
    public Uni<RunResponse> suspendRun(String runId, String reason, String waitingOnNodeId) {
        checkClosed();
        return Uni.createFrom().failure(new UnsupportedOperationException("gRPC transport not implemented yet"));
    }

    @Override
    public Uni<RunResponse> resumeRun(String runId, Map<String, Object> resumeData, String humanTaskId) {
        checkClosed();
        return Uni.createFrom().failure(new UnsupportedOperationException("gRPC transport not implemented yet"));
    }

    @Override
    public Uni<Void> cancelRun(String runId, String reason) {
        checkClosed();
        return Uni.createFrom().failure(new UnsupportedOperationException("gRPC transport not implemented yet"));
    }

    @Override
    public Uni<Void> signal(String runId, String signalName, String targetNodeId, Map<String, Object> payload) {
        checkClosed();
        return Uni.createFrom().failure(new UnsupportedOperationException("gRPC transport not implemented yet"));
    }

    @Override
    public Uni<ExecutionHistory> getExecutionHistory(String runId) {
        checkClosed();
        return Uni.createFrom().failure(new UnsupportedOperationException("gRPC transport not implemented yet"));
    }

    @Override
    public Uni<List<RunResponse>> queryRuns(String workflowId, String status, int page, int size) {
        checkClosed();
        return Uni.createFrom().failure(new UnsupportedOperationException("gRPC transport not implemented yet"));
    }

    @Override
    public Uni<Long> getActiveRunsCount() {
        checkClosed();
        return Uni.createFrom().failure(new UnsupportedOperationException("gRPC transport not implemented yet"));
    }

    @Override
    public void close() {
        closed.set(true);
    }

    private void checkClosed() {
        if (closed.get()) {
            throw new IllegalStateException("Client is closed");
        }
    }
}
