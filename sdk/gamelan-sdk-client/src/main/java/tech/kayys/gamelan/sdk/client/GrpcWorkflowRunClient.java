package tech.kayys.gamelan.sdk.client;

import io.smallrye.mutiny.Uni;
import tech.kayys.gamelan.engine.run.RunResponse;
import tech.kayys.gamelan.engine.run.CreateRunRequest;
import tech.kayys.gamelan.engine.execution.ExecutionHistory;

import java.util.Map;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * gRPC-based workflow run client
 */
class GrpcWorkflowRunClient implements WorkflowRunClient {

    private final GamelanClientConfig config;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    // gRPC stub would be injected here

    GrpcWorkflowRunClient(GamelanClientConfig config) {
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
    public Uni<RunResponse> createRun(CreateRunRequest request) {
        checkClosed();
        return null;
    }

    @Override
    public Uni<RunResponse> getRun(String runId) {
        checkClosed();
        return null;
    }

    @Override
    public Uni<RunResponse> startRun(String runId) {
        checkClosed();
        return null;
    }

    @Override
    public Uni<RunResponse> suspendRun(String runId, String reason, String waitingOnNodeId) {
        checkClosed();
        return null;
    }

    @Override
    public Uni<RunResponse> resumeRun(String runId, Map<String, Object> resumeData, String humanTaskId) {
        checkClosed();
        return null;
    }

    @Override
    public Uni<Void> cancelRun(String runId, String reason) {
        checkClosed();
        return null;
    }

    @Override
    public Uni<Void> signal(String runId, String signalName, String targetNodeId, Map<String, Object> payload) {
        checkClosed();
        return null;
    }

    @Override
    public Uni<ExecutionHistory> getExecutionHistory(String runId) {
        checkClosed();
        return null;
    }

    @Override
    public Uni<List<RunResponse>> queryRuns(String workflowId, String status, int page, int size) {
        checkClosed();
        return null;
    }

    @Override
    public Uni<Long> getActiveRunsCount() {
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
