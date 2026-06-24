package tech.kayys.gamelan.sdk.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.protobuf.Empty;
import com.google.protobuf.Timestamp;
import io.smallrye.mutiny.Uni;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tech.kayys.gamelan.engine.run.CreateRunRequest;
import tech.kayys.gamelan.engine.run.RunResponse;
import tech.kayys.gamelan.grpc.v1.CountResponse;
import tech.kayys.gamelan.grpc.v1.ExecutionHistoryResponse;
import tech.kayys.gamelan.grpc.v1.QueryRunsResponse;
import tech.kayys.gamelan.grpc.v1.RunStatus;

import java.time.Instant;
import java.util.Map;

class GrpcWorkflowRunClientTest {

    private CapturingGateway gateway;
    private GrpcWorkflowRunClient client;

    @BeforeEach
    void setUp() {
        GamelanClientConfig config = GamelanClientConfig.builder()
                .endpoint("localhost:9090")
                .grpc()
                .tenantId("test-tenant")
                .build();
        gateway = new CapturingGateway();
        client = new GrpcWorkflowRunClient(config, gateway);
    }

    @Test
    void createRunMapsDomainRequestToGrpcRequest() {
        CreateRunRequest request = CreateRunRequest.builder()
                .workflowId("agent-workflow")
                .workflowVersion("2.1.0")
                .inputs(Map.of("goal", "ship"))
                .correlationId("corr-1")
                .autoStart(true)
                .build();

        RunResponse response = client.createRun(request).await().indefinitely();

        assertEquals("run-1", response.getRunId());
        assertEquals("agent-workflow", gateway.createRunRequest.getWorkflowDefinitionId());
        assertEquals("2.1.0", gateway.createRunRequest.getWorkflowVersion());
        assertEquals("corr-1", gateway.createRunRequest.getCorrelationId());
        assertEquals("test-tenant", gateway.createRunRequest.getTenantId());
        assertEquals(true, gateway.createRunRequest.getAutoStart());
        assertEquals("ship", GrpcWorkflowRunClient.structToMap(gateway.createRunRequest.getInputs()).get("goal"));
    }

    @Test
    void signalMapsIdempotencyOutsidePayloadAndLeavesBlankTargetUnset() {
        client.signal("run-1", "approved", "  ", Map.of("approval.result", "yes"), " signal-1 ")
                .await().indefinitely();

        assertEquals("run-1", gateway.signalRequest.getRunId());
        assertEquals("approved", gateway.signalRequest.getSignalName());
        assertEquals("signal-1", gateway.signalRequest.getIdempotencyKey());
        assertEquals("", gateway.signalRequest.getTargetNodeId());
        assertEquals("yes", GrpcWorkflowRunClient.structToMap(gateway.signalRequest.getPayload()).get("approval.result"));
    }

    @Test
    void queryAndCountUseTenantScopedGrpcRequests() {
        assertEquals(3L, client.getActiveRunsCount().await().indefinitely());
        assertEquals("test-tenant", gateway.countRequest.getTenantId());

        assertEquals(1, client.queryRuns("wf-1", "RUNNING", 2, 50).await().indefinitely().size());
        assertEquals("test-tenant", gateway.queryRunsRequest.getTenantId());
        assertEquals("wf-1", gateway.queryRunsRequest.getWorkflowDefinitionId());
        assertEquals("RUNNING", gateway.queryRunsRequest.getStatus());
        assertEquals(2, gateway.queryRunsRequest.getPage());
        assertEquals(50, gateway.queryRunsRequest.getSize());
    }

    @Test
    void closeStateRejectsCallsSynchronously() {
        client.close();
        assertThrows(IllegalStateException.class, () -> client.getRun("any"));
    }

    private static final class CapturingGateway implements GrpcWorkflowRunClient.WorkflowRunGrpcGateway {
        private tech.kayys.gamelan.grpc.v1.CreateRunRequest createRunRequest;
        private tech.kayys.gamelan.grpc.v1.SignalRequest signalRequest;
        private tech.kayys.gamelan.grpc.v1.QueryRunsRequest queryRunsRequest;
        private tech.kayys.gamelan.grpc.v1.GetActiveRunsCountRequest countRequest;

        @Override
        public Uni<tech.kayys.gamelan.grpc.v1.RunResponse> createRun(
                tech.kayys.gamelan.grpc.v1.CreateRunRequest request) {
            this.createRunRequest = request;
            return Uni.createFrom().item(protoRun("run-1", "agent-workflow"));
        }

        @Override
        public Uni<tech.kayys.gamelan.grpc.v1.RunResponse> getRun(
                tech.kayys.gamelan.grpc.v1.GetRunRequest request) {
            return Uni.createFrom().item(protoRun(request.getRunId(), "agent-workflow"));
        }

        @Override
        public Uni<tech.kayys.gamelan.grpc.v1.RunResponse> startRun(
                tech.kayys.gamelan.grpc.v1.StartRunRequest request) {
            return Uni.createFrom().item(protoRun(request.getRunId(), "agent-workflow"));
        }

        @Override
        public Uni<tech.kayys.gamelan.grpc.v1.RunResponse> suspendRun(
                tech.kayys.gamelan.grpc.v1.SuspendRunRequest request) {
            return Uni.createFrom().item(protoRun(request.getRunId(), "agent-workflow"));
        }

        @Override
        public Uni<tech.kayys.gamelan.grpc.v1.RunResponse> resumeRun(
                tech.kayys.gamelan.grpc.v1.ResumeRunRequest request) {
            return Uni.createFrom().item(protoRun(request.getRunId(), "agent-workflow"));
        }

        @Override
        public Uni<Empty> cancelRun(tech.kayys.gamelan.grpc.v1.CancelRunRequest request) {
            return Uni.createFrom().item(Empty.getDefaultInstance());
        }

        @Override
        public Uni<Empty> signalRun(tech.kayys.gamelan.grpc.v1.SignalRequest request) {
            this.signalRequest = request;
            return Uni.createFrom().item(Empty.getDefaultInstance());
        }

        @Override
        public Uni<ExecutionHistoryResponse> getExecutionHistory(
                tech.kayys.gamelan.grpc.v1.GetExecutionHistoryRequest request) {
            return Uni.createFrom().item(ExecutionHistoryResponse.newBuilder()
                    .setRunId(request.getRunId())
                    .build());
        }

        @Override
        public Uni<QueryRunsResponse> queryRuns(tech.kayys.gamelan.grpc.v1.QueryRunsRequest request) {
            this.queryRunsRequest = request;
            return Uni.createFrom().item(QueryRunsResponse.newBuilder()
                    .addRuns(protoRun("run-1", request.getWorkflowDefinitionId()))
                    .build());
        }

        @Override
        public Uni<CountResponse> getActiveRunsCount(
                tech.kayys.gamelan.grpc.v1.GetActiveRunsCountRequest request) {
            this.countRequest = request;
            return Uni.createFrom().item(CountResponse.newBuilder().setCount(3L).build());
        }

        private static tech.kayys.gamelan.grpc.v1.RunResponse protoRun(String runId, String workflowId) {
            Instant now = Instant.parse("2026-05-26T00:00:00Z");
            return tech.kayys.gamelan.grpc.v1.RunResponse.newBuilder()
                    .setRunId(runId)
                    .setTenantId("test-tenant")
                    .setWorkflowDefinitionId(workflowId)
                    .setWorkflowVersion("2.1.0")
                    .setStatus(RunStatus.RUN_STATUS_RUNNING)
                    .setCreatedAt(Timestamp.newBuilder()
                            .setSeconds(now.getEpochSecond())
                            .setNanos(now.getNano())
                            .build())
                    .setVariables(GrpcWorkflowRunClient.mapToStruct(Map.of("result", "ok")))
                    .build();
        }
    }
}
