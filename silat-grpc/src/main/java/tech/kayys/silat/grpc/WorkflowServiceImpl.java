package tech.kayys.silat.grpc;

import com.google.protobuf.Empty;
import com.google.protobuf.Struct;
import com.google.protobuf.Timestamp;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import io.quarkus.grpc.GrpcService;
import io.smallrye.common.annotation.Blocking;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import tech.kayys.silat.engine.WorkflowRunManager;
import tech.kayys.silat.grpc.v1.*;
import tech.kayys.silat.model.NodeId;
import tech.kayys.silat.model.Signal;
import tech.kayys.silat.model.TenantId;
import tech.kayys.silat.model.WorkflowDefinitionId;
import tech.kayys.silat.model.WorkflowRunId;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * ============================================================================
 * gRPC WORKFLOW SERVICE IMPLEMENTATION
 * ============================================================================
 * 
 * High-performance gRPC service for workflow operations.
 * 
 * Features:
 * - Reactive Mutiny integration
 * - Server streaming for real-time updates
 * - Optimized serialization with Protocol Buffers
 * - Built-in interceptors for auth and tenant isolation
 */
@GrpcService
public class WorkflowServiceImpl implements WorkflowService {

        private static final Logger LOG = LoggerFactory.getLogger(WorkflowServiceImpl.class);

        @Inject
        WorkflowRunManager runManager;

        @Inject
        GrpcMapper mapper;

        @Inject
        GrpcTenantInterceptor tenantInterceptor;

        // ==================== CREATE RUN ====================

        @Override
        public Uni<RunResponse> createRun(
                        CreateRunRequest request) {

                LOG.info("gRPC: Creating workflow run for definition: {}",
                                request.getWorkflowDefinitionId());

                TenantId tenantId = tenantInterceptor.getCurrentTenantId();

                // Convert protobuf to domain object
                tech.kayys.silat.model.CreateRunRequest domainRequest = new tech.kayys.silat.model.CreateRunRequest(
                                request.getWorkflowDefinitionId(),
                                null, // version/name?
                                mapper.structToMap(request.getInputs()),
                                null, // trigger/source?
                                false // isDryRun/sync?
                );
                // domainRequest.setLabels(request.getLabelsMap()); // If setters exist

                return runManager.createRun(domainRequest, tenantId)
                                .map(mapper::toProtoRunResponse)
                                .onFailure().transform(this::mapException);
        }

        // ==================== GET RUN ====================

        @Override
        public Uni<RunResponse> getRun(
                        GetRunRequest request) {

                LOG.debug("gRPC: Getting workflow run: {}", request.getRunId());

                TenantId tenantId = TenantId.of(request.getTenantId());
                WorkflowRunId runId = WorkflowRunId.of(request.getRunId());

                return runManager.getRun(runId, tenantId)
                                .map(mapper::toProtoRunResponse)
                                .onFailure().transform(this::mapException);
        }

        // ==================== START RUN ====================

        @Override
        public Uni<RunResponse> startRun(
                        StartRunRequest request) {

                LOG.info("gRPC: Starting workflow run: {}", request.getRunId());

                TenantId tenantId = TenantId.of(request.getTenantId());
                WorkflowRunId runId = WorkflowRunId.of(request.getRunId());

                return runManager.startRun(runId, tenantId)
                                .map(mapper::toProtoRunResponse)
                                .onFailure().transform(this::mapException);
        }

        // ==================== SUSPEND RUN ====================

        @Override
        public Uni<RunResponse> suspendRun(
                        SuspendRunRequest request) {

                LOG.info("gRPC: Suspending workflow run: {}", request.getRunId());

                TenantId tenantId = TenantId.of(request.getTenantId());
                WorkflowRunId runId = WorkflowRunId.of(request.getRunId());
                NodeId nodeId = !request.getWaitingOnNodeId().isEmpty() ? NodeId.of(request.getWaitingOnNodeId())
                                : null;

                return runManager.suspendRun(runId, tenantId, request.getReason(), nodeId)
                                .map(mapper::toProtoRunResponse)
                                .onFailure().transform(this::mapException);
        }

        // ==================== RESUME RUN ====================

        @Override
        public Uni<RunResponse> resumeRun(
                        ResumeRunRequest request) {

                LOG.info("gRPC: Resuming workflow run: {}", request.getRunId());

                TenantId tenantId = TenantId.of(request.getTenantId());
                WorkflowRunId runId = WorkflowRunId.of(request.getRunId());
                Map<String, Object> resumeData = mapper.structToMap(request.getResumeData());

                return runManager.resumeRun(runId, tenantId, resumeData)
                                .map(mapper::toProtoRunResponse)
                                .onFailure().transform(this::mapException);
        }

        // ==================== CANCEL RUN ====================

        @Override
        public Uni<Empty> cancelRun(CancelRunRequest request) {

                LOG.info("gRPC: Cancelling workflow run: {}", request.getRunId());

                TenantId tenantId = TenantId.of(request.getTenantId());
                WorkflowRunId runId = WorkflowRunId.of(request.getRunId());

                return runManager.cancelRun(runId, tenantId, request.getReason())
                                .map(v -> Empty.getDefaultInstance())
                                .onFailure().transform(this::mapException);
        }

        // ==================== SIGNAL RUN ====================

        @Override
        public Uni<Empty> signalRun(SignalRequest request) {

                LOG.info("gRPC: Sending signal to run: {}", request.getRunId());

                WorkflowRunId runId = WorkflowRunId.of(request.getRunId());

                Signal signal = new Signal(
                                request.getSignalName(),
                                NodeId.of(request.getTargetNodeId()),
                                mapper.structToMap(request.getPayload()),
                                Instant.now());

                return runManager.signal(runId, signal)
                                .map(v -> Empty.getDefaultInstance())
                                .onFailure().transform(this::mapException);
        }

        // ==================== GET EXECUTION HISTORY ====================

        @Override
        public Uni<ExecutionHistoryResponse> getExecutionHistory(
                        GetExecutionHistoryRequest request) {

                LOG.debug("gRPC: Getting execution history for run: {}", request.getRunId());

                TenantId tenantId = TenantId.of(request.getTenantId());
                WorkflowRunId runId = WorkflowRunId.of(request.getRunId());

                return runManager.getExecutionHistory(runId, tenantId)
                                .map(mapper::toProtoHistoryResponse)
                                .onFailure().transform(this::mapException);
        }

        // ==================== QUERY RUNS ====================

        @Override
        public Uni<QueryRunsResponse> queryRuns(QueryRunsRequest request) {

                LOG.debug("gRPC: Querying runs for tenant: {}", request.getTenantId());

                TenantId tenantId = TenantId.of(request.getTenantId());
                WorkflowDefinitionId definitionId = !request.getWorkflowDefinitionId().isEmpty()
                                ? WorkflowDefinitionId.of(request.getWorkflowDefinitionId())
                                : null;
                tech.kayys.silat.model.RunStatus status = !request.getStatus().isEmpty()
                                ? tech.kayys.silat.model.RunStatus.valueOf(request.getStatus())
                                : null;

                return runManager.queryRuns(
                                tenantId,
                                definitionId,
                                status,
                                request.getPage(),
                                request.getSize())
                                .map(runs -> {
                                        QueryRunsResponse.Builder builder = QueryRunsResponse.newBuilder()
                                                        .setPage(request.getPage())
                                                        .setSize(request.getSize())
                                                        .setTotalElements(runs.size())
                                                        .setHasMore(false); // simplified

                                        runs.forEach(run -> builder.addRuns(mapper.toProtoRunResponse(run)));

                                        return builder.build();
                                })
                                .onFailure().transform(this::mapException);
        }

        // ==================== GET ACTIVE RUNS COUNT ====================

        @Override
        public Uni<CountResponse> getActiveRunsCount(
                        GetActiveRunsCountRequest request) {

                TenantId tenantId = TenantId.of(request.getTenantId());

                return runManager.getActiveRunsCount(tenantId)
                                .map(count -> CountResponse.newBuilder()
                                                .setCount(count)
                                                .build())
                                .onFailure().transform(this::mapException);
        }

        // ==================== STREAM RUN STATUS (SERVER STREAMING)
        // ====================

        @Override
        public Multi<RunStatusUpdate> streamRunStatus(
                        StreamRunStatusRequest request) {

                LOG.info("gRPC: Starting status stream for {} runs",
                                request.getRunIdsCount());

                // This would connect to an event stream (Kafka, Redis Pub/Sub, etc.)
                // For now, return empty stream

                return Multi.createFrom().empty();
        }

        // ==================== ERROR HANDLING ====================

        private Throwable mapException(Throwable throwable) {
                LOG.error("gRPC error", throwable);

                if (throwable instanceof java.util.NoSuchElementException) {
                        return Status.NOT_FOUND
                                        .withDescription(throwable.getMessage())
                                        .asRuntimeException();
                } else if (throwable instanceof IllegalArgumentException ||
                                throwable instanceof IllegalStateException) {
                        return Status.INVALID_ARGUMENT
                                        .withDescription(throwable.getMessage())
                                        .asRuntimeException();
                } else if (throwable instanceof SecurityException) {
                        return Status.PERMISSION_DENIED
                                        .withDescription(throwable.getMessage())
                                        .asRuntimeException();
                } else {
                        return Status.INTERNAL
                                        .withDescription("Internal server error")
                                        .asRuntimeException();
                }
        }
}
