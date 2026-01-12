package tech.kayys.silat.dispatcher;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import jakarta.enterprise.context.ApplicationScoped;
import tech.kayys.silat.model.ExecutorInfo;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class GrpcClientFactory {

    private final Map<String, ExecutorGrpc.ExecutorStub> stubs = new ConcurrentHashMap<>();

    public ExecutorGrpc.ExecutorStub getStub(ExecutorInfo executor) {
        return stubs.computeIfAbsent(executor.executorId(), id -> {
            // Create gRPC channel - OpenTelemetry instrumentation will be applied automatically by Quarkus
            ManagedChannel channel = ManagedChannelBuilder.forTarget(executor.endpoint())
                    .usePlaintext()
                    .build();
            return ExecutorGrpc.newStub(channel);
        });
    }
}
