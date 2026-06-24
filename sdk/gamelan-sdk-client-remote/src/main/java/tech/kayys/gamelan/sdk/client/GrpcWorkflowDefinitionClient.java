package tech.kayys.gamelan.sdk.client;

import com.google.protobuf.Empty;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.MetadataUtils;
import io.smallrye.mutiny.Uni;
import tech.kayys.gamelan.engine.workflow.WorkflowDefinition;
import tech.kayys.gamelan.grpc.GrpcWorkflowDefinitionMapper;
import tech.kayys.gamelan.grpc.v1.MutinyWorkflowDefinitionServiceGrpc;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * gRPC-based workflow definition client.
 */
public class GrpcWorkflowDefinitionClient implements WorkflowDefinitionClient {

    private final GamelanClientConfig config;
    private final WorkflowDefinitionGrpcGateway gateway;
    private final ManagedChannel channel;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    GrpcWorkflowDefinitionClient(GamelanClientConfig config) {
        this(config, openChannel(config));
    }

    GrpcWorkflowDefinitionClient(GamelanClientConfig config, WorkflowDefinitionGrpcGateway gateway) {
        this.config = config;
        this.gateway = gateway;
        this.channel = null;
    }

    private GrpcWorkflowDefinitionClient(GamelanClientConfig config, ManagedChannel channel) {
        this.config = config;
        this.channel = channel;
        this.gateway = new StubWorkflowDefinitionGrpcGateway(config, channel);
    }

    /**
     * Get the client configuration.
     */
    public GamelanClientConfig config() {
        return config;
    }

    @Override
    public Uni<WorkflowDefinition> createWorkflow(WorkflowDefinition request) {
        checkClosed();
        return gateway.createDefinition(GrpcWorkflowDefinitionMapper.toProtoCreateRequest(request, config.tenantId()))
                .map(response -> GrpcWorkflowDefinitionMapper.toDomainDefinition(response, config.tenantId()))
                .onFailure(StatusRuntimeException.class).transform(GrpcWorkflowDefinitionClient::toClientException);
    }

    @Override
    public Uni<WorkflowDefinition> getWorkflow(String definitionId) {
        checkClosed();
        return gateway.getDefinition(tech.kayys.gamelan.grpc.v1.GetDefinitionRequest.newBuilder()
                .setTenantId(config.tenantId())
                .setDefinitionId(definitionId)
                .build())
                .map(response -> GrpcWorkflowDefinitionMapper.toDomainDefinition(response, config.tenantId()))
                .onFailure(StatusRuntimeException.class).transform(GrpcWorkflowDefinitionClient::toClientException);
    }

    @Override
    public Uni<WorkflowDefinition> getWorkflowByName(String name) {
        checkClosed();
        return gateway.getDefinition(tech.kayys.gamelan.grpc.v1.GetDefinitionRequest.newBuilder()
                .setTenantId(config.tenantId())
                .setName(name)
                .build())
                .map(response -> GrpcWorkflowDefinitionMapper.toDomainDefinition(response, config.tenantId()))
                .onFailure(StatusRuntimeException.class).transform(GrpcWorkflowDefinitionClient::toClientException);
    }

    @Override
    public Uni<List<WorkflowDefinition>> listWorkflows() {
        return listWorkflows(true);
    }

    @Override
    public Uni<List<WorkflowDefinition>> listWorkflows(boolean activeOnly) {
        checkClosed();
        return gateway.listDefinitions(tech.kayys.gamelan.grpc.v1.ListDefinitionsRequest.newBuilder()
                .setTenantId(config.tenantId())
                .setActiveOnly(activeOnly)
                .build())
                .map(response -> response.getDefinitionsList().stream()
                        .map(definition -> GrpcWorkflowDefinitionMapper.toDomainDefinition(definition, config.tenantId()))
                        .toList())
                .onFailure(StatusRuntimeException.class).transform(GrpcWorkflowDefinitionClient::toClientException);
    }

    @Override
    public Uni<Void> deleteWorkflow(String definitionId) {
        checkClosed();
        return gateway.deleteDefinition(tech.kayys.gamelan.grpc.v1.DeleteDefinitionRequest.newBuilder()
                .setTenantId(config.tenantId())
                .setDefinitionId(definitionId)
                .build())
                .replaceWithVoid()
                .onFailure(StatusRuntimeException.class).transform(GrpcWorkflowDefinitionClient::toClientException);
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

    private static GamelanClientException toClientException(Throwable throwable) {
        StatusRuntimeException status = (StatusRuntimeException) throwable;
        return new GamelanClientException(
                "gRPC workflow definition request failed: " + status.getStatus(),
                status.getStatus().getCode().value(),
                status);
    }

    interface WorkflowDefinitionGrpcGateway {
        Uni<tech.kayys.gamelan.grpc.v1.DefinitionResponse> createDefinition(
                tech.kayys.gamelan.grpc.v1.CreateDefinitionRequest request);

        Uni<tech.kayys.gamelan.grpc.v1.DefinitionResponse> getDefinition(
                tech.kayys.gamelan.grpc.v1.GetDefinitionRequest request);

        Uni<tech.kayys.gamelan.grpc.v1.ListDefinitionsResponse> listDefinitions(
                tech.kayys.gamelan.grpc.v1.ListDefinitionsRequest request);

        Uni<Empty> deleteDefinition(tech.kayys.gamelan.grpc.v1.DeleteDefinitionRequest request);
    }

    private static final class StubWorkflowDefinitionGrpcGateway implements WorkflowDefinitionGrpcGateway {
        private final GamelanClientConfig config;
        private final MutinyWorkflowDefinitionServiceGrpc.MutinyWorkflowDefinitionServiceStub stub;

        private StubWorkflowDefinitionGrpcGateway(GamelanClientConfig config, ManagedChannel channel) {
            this.config = config;
            this.stub = MutinyWorkflowDefinitionServiceGrpc.newMutinyStub(channel)
                    .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(metadata(config)));
        }

        @Override
        public Uni<tech.kayys.gamelan.grpc.v1.DefinitionResponse> createDefinition(
                tech.kayys.gamelan.grpc.v1.CreateDefinitionRequest request) {
            return deadlineStub().createDefinition(request);
        }

        @Override
        public Uni<tech.kayys.gamelan.grpc.v1.DefinitionResponse> getDefinition(
                tech.kayys.gamelan.grpc.v1.GetDefinitionRequest request) {
            return deadlineStub().getDefinition(request);
        }

        @Override
        public Uni<tech.kayys.gamelan.grpc.v1.ListDefinitionsResponse> listDefinitions(
                tech.kayys.gamelan.grpc.v1.ListDefinitionsRequest request) {
            return deadlineStub().listDefinitions(request);
        }

        @Override
        public Uni<Empty> deleteDefinition(tech.kayys.gamelan.grpc.v1.DeleteDefinitionRequest request) {
            return deadlineStub().deleteDefinition(request);
        }

        private MutinyWorkflowDefinitionServiceGrpc.MutinyWorkflowDefinitionServiceStub deadlineStub() {
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
