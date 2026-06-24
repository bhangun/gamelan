package tech.kayys.gamelan.dispatcher;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import tech.kayys.gamelan.engine.executor.ExecutorInfo;
import tech.kayys.gamelan.engine.node.NodeExecutionTask;
import tech.kayys.gamelan.engine.protocol.CommunicationType;

@ApplicationScoped
public class GrpcStreamTaskDispatcher implements TaskDispatcher {

    public static final String METADATA_GRPC_DELIVERY = "gamelan.grpc.delivery";
    public static final String METADATA_TASK_DELIVERY = "gamelan.task.delivery";
    public static final String METADATA_DELIVERY_MODE = "gamelan.delivery.mode";

    @Inject
    GrpcTaskStreamBroker streamBroker;

    @ConfigProperty(name = "gamelan.grpc.task-stream.default-enabled", defaultValue = "false")
    boolean defaultEnabled;

    @Override
    public Uni<Void> dispatch(NodeExecutionTask task, ExecutorInfo executor) {
        Objects.requireNonNull(task, "NodeExecutionTask cannot be null");
        Objects.requireNonNull(executor, "ExecutorInfo cannot be null");
        return streamBroker.assign(executor.executorId(), task);
    }

    @Override
    public boolean supports(ExecutorInfo executor) {
        if (executor == null || executor.communicationType() != CommunicationType.GRPC) {
            return false;
        }
        return defaultEnabled || endpointMissing(executor) || streamDeliveryRequested(executor.metadata());
    }

    @Override
    public Uni<Boolean> isHealthy() {
        return Uni.createFrom().item(streamBroker != null);
    }

    @Override
    public int getPriority() {
        return 9;
    }

    private boolean endpointMissing(ExecutorInfo executor) {
        return executor.endpoint() == null || executor.endpoint().isBlank();
    }

    private boolean streamDeliveryRequested(Map<String, String> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return false;
        }
        return isStreamValue(metadata.get(METADATA_GRPC_DELIVERY))
                || isStreamValue(metadata.get(METADATA_TASK_DELIVERY))
                || isStreamValue(metadata.get(METADATA_DELIVERY_MODE))
                || isStreamValue(metadata.get("delivery"))
                || isStreamValue(metadata.get("transport"));
    }

    private boolean isStreamValue(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return normalized.equals("stream")
                || normalized.equals("pull")
                || normalized.equals("grpc-stream")
                || normalized.equals("grpc_stream")
                || normalized.equals("server-stream");
    }
}
