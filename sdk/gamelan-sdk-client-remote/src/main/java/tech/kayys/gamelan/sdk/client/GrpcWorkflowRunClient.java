package tech.kayys.gamelan.sdk.client;

import com.google.protobuf.Struct;
import com.google.protobuf.Timestamp;
import com.google.protobuf.util.JsonFormat;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.MetadataUtils;
import io.smallrye.mutiny.Uni;
import tech.kayys.gamelan.engine.execution.ExecutionHistory;
import tech.kayys.gamelan.engine.run.CreateRunRequest;
import tech.kayys.gamelan.engine.run.RunResponse;
import tech.kayys.gamelan.engine.workflow.WorkflowId;
import tech.kayys.gamelan.engine.workflow.WorkflowRunId;
import tech.kayys.gamelan.grpc.v1.MutinyWorkflowServiceGrpc;
import tech.kayys.gamelan.grpc.v1.RunStatus;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * gRPC-based implementation of {@link WorkflowRunClient}.
 */
public class GrpcWorkflowRunClient implements WorkflowRunClient {

    private static final com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper()
            .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
            .registerModule(new com.fasterxml.jackson.module.paramnames.ParameterNamesModule())
            .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final GamelanClientConfig config;
    private final WorkflowRunGrpcGateway gateway;
    private final ManagedChannel channel;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    GrpcWorkflowRunClient(GamelanClientConfig config) {
        this(config, openChannel(config));
    }

    GrpcWorkflowRunClient(GamelanClientConfig config, WorkflowRunGrpcGateway gateway) {
        this.config = config;
        this.gateway = gateway;
        this.channel = null;
    }

    private GrpcWorkflowRunClient(GamelanClientConfig config, ManagedChannel channel) {
        this.config = config;
        this.channel = channel;
        this.gateway = new StubWorkflowRunGrpcGateway(config, channel);
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
        tech.kayys.gamelan.grpc.v1.CreateRunRequest.Builder builder =
                tech.kayys.gamelan.grpc.v1.CreateRunRequest.newBuilder()
                        .setTenantId(config.tenantId())
                        .setWorkflowDefinitionId(nonNull(request.getWorkflowId()))
                        .setWorkflowVersion(nonNull(request.getWorkflowVersion()))
                        .setCorrelationId(nonNull(request.getCorrelationId()))
                        .setAutoStart(request.isAutoStart())
                        .setInputs(mapToStruct(request.getInputs()));

        return gateway.createRun(builder.build())
                .map(GrpcWorkflowRunClient::toDomainRunResponse)
                .onFailure(StatusRuntimeException.class).transform(GrpcWorkflowRunClient::toClientException);
    }

    @Override
    public Uni<RunResponse> getRun(String runId) {
        checkClosed();
        return gateway.getRun(tech.kayys.gamelan.grpc.v1.GetRunRequest.newBuilder()
                .setTenantId(config.tenantId())
                .setRunId(runId)
                .build())
                .map(GrpcWorkflowRunClient::toDomainRunResponse)
                .onFailure(StatusRuntimeException.class).transform(GrpcWorkflowRunClient::toClientException);
    }

    @Override
    public Uni<RunResponse> startRun(String runId) {
        checkClosed();
        return gateway.startRun(tech.kayys.gamelan.grpc.v1.StartRunRequest.newBuilder()
                .setTenantId(config.tenantId())
                .setRunId(runId)
                .build())
                .map(GrpcWorkflowRunClient::toDomainRunResponse)
                .onFailure(StatusRuntimeException.class).transform(GrpcWorkflowRunClient::toClientException);
    }

    @Override
    public Uni<RunResponse> suspendRun(String runId, String reason, String waitingOnNodeId) {
        checkClosed();
        tech.kayys.gamelan.grpc.v1.SuspendRunRequest.Builder builder =
                tech.kayys.gamelan.grpc.v1.SuspendRunRequest.newBuilder()
                        .setTenantId(config.tenantId())
                        .setRunId(runId)
                        .setReason(nonNull(reason));
        if (waitingOnNodeId != null && !waitingOnNodeId.isBlank()) {
            builder.setWaitingOnNodeId(waitingOnNodeId.trim());
        }
        return gateway.suspendRun(builder.build())
                .map(GrpcWorkflowRunClient::toDomainRunResponse)
                .onFailure(StatusRuntimeException.class).transform(GrpcWorkflowRunClient::toClientException);
    }

    @Override
    public Uni<RunResponse> resumeRun(String runId, Map<String, Object> resumeData, String humanTaskId) {
        checkClosed();
        tech.kayys.gamelan.grpc.v1.ResumeRunRequest.Builder builder =
                tech.kayys.gamelan.grpc.v1.ResumeRunRequest.newBuilder()
                        .setTenantId(config.tenantId())
                        .setRunId(runId)
                        .setResumeData(mapToStruct(resumeData));
        if (humanTaskId != null && !humanTaskId.isBlank()) {
            builder.setHumanTaskId(humanTaskId.trim());
        }
        return gateway.resumeRun(builder.build())
                .map(GrpcWorkflowRunClient::toDomainRunResponse)
                .onFailure(StatusRuntimeException.class).transform(GrpcWorkflowRunClient::toClientException);
    }

    @Override
    public Uni<Void> cancelRun(String runId, String reason) {
        checkClosed();
        return gateway.cancelRun(tech.kayys.gamelan.grpc.v1.CancelRunRequest.newBuilder()
                .setTenantId(config.tenantId())
                .setRunId(runId)
                .setReason(nonNull(reason))
                .build())
                .replaceWithVoid()
                .onFailure(StatusRuntimeException.class).transform(GrpcWorkflowRunClient::toClientException);
    }

    @Override
    public Uni<Void> signal(String runId, String signalName, String targetNodeId, Map<String, Object> payload) {
        return signal(runId, signalName, targetNodeId, payload, null);
    }

    @Override
    public Uni<Void> signal(
            String runId,
            String signalName,
            String targetNodeId,
            Map<String, Object> payload,
            String idempotencyKey) {
        checkClosed();
        tech.kayys.gamelan.grpc.v1.SignalRequest.Builder builder =
                tech.kayys.gamelan.grpc.v1.SignalRequest.newBuilder()
                        .setRunId(runId)
                        .setSignalName(signalName)
                        .setPayload(mapToStruct(payload));
        if (targetNodeId != null && !targetNodeId.isBlank()) {
            builder.setTargetNodeId(targetNodeId.trim());
        }
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            builder.setIdempotencyKey(idempotencyKey.trim());
        }
        return gateway.signalRun(builder.build())
                .replaceWithVoid()
                .onFailure(StatusRuntimeException.class).transform(GrpcWorkflowRunClient::toClientException);
    }

    @Override
    public Uni<ExecutionHistory> getExecutionHistory(String runId) {
        checkClosed();
        return gateway.getExecutionHistory(tech.kayys.gamelan.grpc.v1.GetExecutionHistoryRequest.newBuilder()
                .setTenantId(config.tenantId())
                .setRunId(runId)
                .build())
                .map(response -> toDomainExecutionHistory(response, config.tenantId()))
                .onFailure(StatusRuntimeException.class).transform(GrpcWorkflowRunClient::toClientException);
    }

    @Override
    public Uni<List<RunResponse>> queryRuns(String workflowId, String status, int page, int size) {
        checkClosed();
        tech.kayys.gamelan.grpc.v1.QueryRunsRequest.Builder builder =
                tech.kayys.gamelan.grpc.v1.QueryRunsRequest.newBuilder()
                        .setTenantId(config.tenantId())
                        .setPage(page)
                        .setSize(size);
        if (workflowId != null && !workflowId.isBlank()) {
            builder.setWorkflowDefinitionId(workflowId.trim());
        }
        if (status != null && !status.isBlank()) {
            builder.setStatus(status.trim());
        }
        return gateway.queryRuns(builder.build())
                .map(response -> response.getRunsList().stream()
                        .map(GrpcWorkflowRunClient::toDomainRunResponse)
                        .toList())
                .onFailure(StatusRuntimeException.class).transform(GrpcWorkflowRunClient::toClientException);
    }

    @Override
    public Uni<Long> getActiveRunsCount() {
        checkClosed();
        return gateway.getActiveRunsCount(tech.kayys.gamelan.grpc.v1.GetActiveRunsCountRequest.newBuilder()
                .setTenantId(config.tenantId())
                .build())
                .map(tech.kayys.gamelan.grpc.v1.CountResponse::getCount)
                .onFailure(StatusRuntimeException.class).transform(GrpcWorkflowRunClient::toClientException);
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true) && channel != null) {
            channel.shutdown();
            try {
                if (!channel.awaitTermination(1, TimeUnit.SECONDS)) {
                    channel.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                channel.shutdownNow();
            }
        }
    }

    private void checkClosed() {
        if (closed.get()) {
            throw new IllegalStateException("Client is closed");
        }
    }

    private static ManagedChannel openChannel(GamelanClientConfig config) {
        ManagedChannelBuilder<?> builder = ManagedChannelBuilder.forTarget(grpcTarget(config.endpoint()));
        if (isPlaintext(config.endpoint())) {
            builder.usePlaintext();
        } else {
            builder.useTransportSecurity();
        }
        return builder.build();
    }

    private static String grpcTarget(String endpoint) {
        if (endpoint == null || endpoint.isBlank()) {
            throw new IllegalArgumentException("gRPC endpoint cannot be null or blank");
        }
        String trimmed = endpoint.trim();
        if (!trimmed.contains("://")) {
            return trimmed;
        }

        URI uri = URI.create(trimmed);
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            return trimmed;
        }
        int port = uri.getPort();
        if (port < 0) {
            port = isPlaintext(trimmed) ? 80 : 443;
        }
        return host + ":" + port;
    }

    private static boolean isPlaintext(String endpoint) {
        String lower = endpoint.toLowerCase(Locale.ROOT);
        return !(lower.startsWith("https://") || lower.startsWith("grpcs://"));
    }

    private static String nonNull(String value) {
        return value != null ? value : "";
    }

    static Struct mapToStruct(Map<String, Object> map) {
        try {
            Struct.Builder builder = Struct.newBuilder();
            JsonFormat.parser().merge(mapper.writeValueAsString(map != null ? map : Map.of()), builder);
            return builder.build();
        } catch (Exception e) {
            throw new GamelanClientException("Failed to serialize gRPC struct payload", e);
        }
    }

    static Map<String, Object> structToMap(Struct struct) {
        try {
            return mapper.readValue(JsonFormat.printer().print(struct),
                    new com.fasterxml.jackson.core.type.TypeReference<>() {
                    });
        } catch (Exception e) {
            throw new GamelanClientException("Failed to deserialize gRPC struct payload", e);
        }
    }

    private static RunResponse toDomainRunResponse(tech.kayys.gamelan.grpc.v1.RunResponse response) {
        Map<String, Object> outputs = response.hasVariables() ? structToMap(response.getVariables()) : Map.of();
        int nodesTotal = response.getNodeExecutionsCount();
        int nodesExecuted = (int) response.getNodeExecutionsMap().values().stream()
                .filter(node -> "COMPLETED".equals(node.getStatus()) || "NODE_EXECUTION_STATUS_COMPLETED".equals(node.getStatus()))
                .count();

        return RunResponse.builder()
                .runId(response.getRunId())
                .workflowId(response.getWorkflowDefinitionId())
                .workflowVersion(blankToNull(response.getWorkflowVersion()))
                .status(toDomainStatus(response.getStatus()))
                .phase(toDomainStatus(response.getStatus()))
                .createdAt(response.hasCreatedAt() ? toInstant(response.getCreatedAt()) : null)
                .startedAt(response.hasStartedAt() ? toInstant(response.getStartedAt()) : null)
                .completedAt(response.hasCompletedAt() ? toInstant(response.getCompletedAt()) : null)
                .durationMs(response.getDurationMs())
                .nodesExecuted(nodesExecuted)
                .nodesTotal(nodesTotal)
                .outputs(outputs)
                .build();
    }

    private static ExecutionHistory toDomainExecutionHistory(
            tech.kayys.gamelan.grpc.v1.ExecutionHistoryResponse response,
            String tenantId) {
        List<ExecutionHistory.ExecutionEventHistory> events = response.getEventsList().stream()
                .map(event -> ExecutionHistory.ExecutionEventHistory.builder()
                        .eventId(event.getEventId())
                        .eventType(toDomainEventType(event.getEventType()))
                        .timestamp(event.hasOccurredAt() ? toInstant(event.getOccurredAt()) : Instant.now())
                        .source("grpc")
                        .payload(event.hasEventData() ? structToMap(event.getEventData()) : Map.of())
                        .metadata(Map.of("sequenceNumber", event.getSequenceNumber()))
                        .build())
                .toList();

        Instant created = events.isEmpty() ? Instant.now() : events.get(0).getTimestamp();
        Instant lastUpdated = events.isEmpty() ? created : events.get(events.size() - 1).getTimestamp();

        return ExecutionHistory.builder()
                .runId(WorkflowRunId.of(response.getRunId()))
                .workflowId(WorkflowId.of("unknown"))
                .workflowVersion("unknown")
                .tenantId(tenantId)
                .created(created)
                .lastUpdated(lastUpdated)
                .events(events)
                .metadata(Map.of("source", "grpc"))
                .build();
    }

    private static ExecutionHistory.ExecutionEventHistory.ExecutionEventType toDomainEventType(String eventType) {
        if (eventType == null || eventType.isBlank()) {
            return ExecutionHistory.ExecutionEventHistory.ExecutionEventType.STATE_UPDATED;
        }
        try {
            return ExecutionHistory.ExecutionEventHistory.ExecutionEventType.valueOf(eventType);
        } catch (IllegalArgumentException ignored) {
            return ExecutionHistory.ExecutionEventHistory.ExecutionEventType.STATE_UPDATED;
        }
    }

    private static Instant toInstant(Timestamp timestamp) {
        return Instant.ofEpochSecond(timestamp.getSeconds(), timestamp.getNanos());
    }

    private static String toDomainStatus(RunStatus status) {
        if (status == null || status == RunStatus.RUN_STATUS_UNSPECIFIED) {
            return "UNKNOWN";
        }
        return status.name().replace("RUN_STATUS_", "");
    }

    private static String blankToNull(String value) {
        return value != null && !value.isBlank() ? value : null;
    }

    private static GamelanClientException toClientException(Throwable throwable) {
        StatusRuntimeException status = (StatusRuntimeException) throwable;
        return new GamelanClientException(
                "gRPC workflow run request failed: " + status.getStatus(),
                status.getStatus().getCode().value(),
                status);
    }

    interface WorkflowRunGrpcGateway {
        Uni<tech.kayys.gamelan.grpc.v1.RunResponse> createRun(
                tech.kayys.gamelan.grpc.v1.CreateRunRequest request);

        Uni<tech.kayys.gamelan.grpc.v1.RunResponse> getRun(
                tech.kayys.gamelan.grpc.v1.GetRunRequest request);

        Uni<tech.kayys.gamelan.grpc.v1.RunResponse> startRun(
                tech.kayys.gamelan.grpc.v1.StartRunRequest request);

        Uni<tech.kayys.gamelan.grpc.v1.RunResponse> suspendRun(
                tech.kayys.gamelan.grpc.v1.SuspendRunRequest request);

        Uni<tech.kayys.gamelan.grpc.v1.RunResponse> resumeRun(
                tech.kayys.gamelan.grpc.v1.ResumeRunRequest request);

        Uni<com.google.protobuf.Empty> cancelRun(
                tech.kayys.gamelan.grpc.v1.CancelRunRequest request);

        Uni<com.google.protobuf.Empty> signalRun(
                tech.kayys.gamelan.grpc.v1.SignalRequest request);

        Uni<tech.kayys.gamelan.grpc.v1.ExecutionHistoryResponse> getExecutionHistory(
                tech.kayys.gamelan.grpc.v1.GetExecutionHistoryRequest request);

        Uni<tech.kayys.gamelan.grpc.v1.QueryRunsResponse> queryRuns(
                tech.kayys.gamelan.grpc.v1.QueryRunsRequest request);

        Uni<tech.kayys.gamelan.grpc.v1.CountResponse> getActiveRunsCount(
                tech.kayys.gamelan.grpc.v1.GetActiveRunsCountRequest request);
    }

    private static final class StubWorkflowRunGrpcGateway implements WorkflowRunGrpcGateway {
        private final GamelanClientConfig config;
        private final MutinyWorkflowServiceGrpc.MutinyWorkflowServiceStub stub;

        private StubWorkflowRunGrpcGateway(GamelanClientConfig config, ManagedChannel channel) {
            this.config = config;
            this.stub = MutinyWorkflowServiceGrpc.newMutinyStub(channel)
                    .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(metadata(config)));
        }

        @Override
        public Uni<tech.kayys.gamelan.grpc.v1.RunResponse> createRun(
                tech.kayys.gamelan.grpc.v1.CreateRunRequest request) {
            return deadlineStub().createRun(request);
        }

        @Override
        public Uni<tech.kayys.gamelan.grpc.v1.RunResponse> getRun(
                tech.kayys.gamelan.grpc.v1.GetRunRequest request) {
            return deadlineStub().getRun(request);
        }

        @Override
        public Uni<tech.kayys.gamelan.grpc.v1.RunResponse> startRun(
                tech.kayys.gamelan.grpc.v1.StartRunRequest request) {
            return deadlineStub().startRun(request);
        }

        @Override
        public Uni<tech.kayys.gamelan.grpc.v1.RunResponse> suspendRun(
                tech.kayys.gamelan.grpc.v1.SuspendRunRequest request) {
            return deadlineStub().suspendRun(request);
        }

        @Override
        public Uni<tech.kayys.gamelan.grpc.v1.RunResponse> resumeRun(
                tech.kayys.gamelan.grpc.v1.ResumeRunRequest request) {
            return deadlineStub().resumeRun(request);
        }

        @Override
        public Uni<com.google.protobuf.Empty> cancelRun(
                tech.kayys.gamelan.grpc.v1.CancelRunRequest request) {
            return deadlineStub().cancelRun(request);
        }

        @Override
        public Uni<com.google.protobuf.Empty> signalRun(
                tech.kayys.gamelan.grpc.v1.SignalRequest request) {
            return deadlineStub().signalRun(request);
        }

        @Override
        public Uni<tech.kayys.gamelan.grpc.v1.ExecutionHistoryResponse> getExecutionHistory(
                tech.kayys.gamelan.grpc.v1.GetExecutionHistoryRequest request) {
            return deadlineStub().getExecutionHistory(request);
        }

        @Override
        public Uni<tech.kayys.gamelan.grpc.v1.QueryRunsResponse> queryRuns(
                tech.kayys.gamelan.grpc.v1.QueryRunsRequest request) {
            return deadlineStub().queryRuns(request);
        }

        @Override
        public Uni<tech.kayys.gamelan.grpc.v1.CountResponse> getActiveRunsCount(
                tech.kayys.gamelan.grpc.v1.GetActiveRunsCountRequest request) {
            return deadlineStub().getActiveRunsCount(request);
        }

        private MutinyWorkflowServiceGrpc.MutinyWorkflowServiceStub deadlineStub() {
            return stub.withDeadlineAfter(config.timeout().toMillis(), TimeUnit.MILLISECONDS);
        }

        private static Metadata metadata(GamelanClientConfig config) {
            Metadata metadata = new Metadata();
            metadata.put(Metadata.Key.of("x-tenant-id", Metadata.ASCII_STRING_MARSHALLER), config.tenantId());
            if (config.apiKey() != null && !config.apiKey().isBlank()) {
                metadata.put(Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER),
                        "Bearer " + config.apiKey().trim());
            }
            config.headers().forEach((key, value) -> {
                if (key != null && !key.isBlank() && value != null) {
                    metadata.put(Metadata.Key.of(key.toLowerCase(Locale.ROOT), Metadata.ASCII_STRING_MARSHALLER),
                            value);
                }
            });
            return metadata;
        }
    }
}
