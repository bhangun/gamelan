package tech.kayys.gamelan.cli;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

import tech.kayys.gamelan.grpc.v1.MutinyWorkflowDefinitionServiceGrpc;
import tech.kayys.gamelan.grpc.v1.MutinyWorkflowServiceGrpc;
import tech.kayys.gamelan.grpc.v1.MutinyExecutorServiceGrpc;

public class GrpcClientManager implements Closeable {

    private final ManagedChannel channel;
    private final MutinyWorkflowDefinitionServiceGrpc.MutinyWorkflowDefinitionServiceStub workflowDefinitionStub;
    private final MutinyWorkflowServiceGrpc.MutinyWorkflowServiceStub workflowStub;
    private final MutinyExecutorServiceGrpc.MutinyExecutorServiceStub executorStub;

    public GrpcClientManager(String serverAddress) {
        // Use configuration value if available, otherwise use provided address
        String actualServerAddress = getServerAddressFromConfig(serverAddress);

        this.channel = ManagedChannelBuilder.forTarget(actualServerAddress)
                .usePlaintext()
                .build();

        this.workflowDefinitionStub = MutinyWorkflowDefinitionServiceGrpc.newMutinyStub(channel);
        this.workflowStub = MutinyWorkflowServiceGrpc.newMutinyStub(channel);
        this.executorStub = MutinyExecutorServiceGrpc.newMutinyStub(channel);
    }

    private String getServerAddressFromConfig(String defaultAddress) {
        try {
            Path configPath = getConfigPath();
            if (Files.exists(configPath)) {
                Properties props = new Properties();
                try (var fis = Files.newInputStream(configPath)) {
                    props.load(fis);
                }

                String configAddress = props.getProperty("server.address");
                if (configAddress != null && !configAddress.trim().isEmpty()) {
                    return configAddress;
                }
            }
        } catch (IOException e) {
            // If there's an issue reading config, fall back to default
            System.err.println("Warning: Could not read configuration file, using default server address: " + defaultAddress);
        }

        return defaultAddress;
    }

    private Path getConfigPath() {
        String userHome = System.getProperty("user.home");
        return Paths.get(userHome, ".gamelan", "config.properties");
    }

    public MutinyWorkflowDefinitionServiceGrpc.MutinyWorkflowDefinitionServiceStub getWorkflowDefinitionStub() {
        return workflowDefinitionStub;
    }

    public MutinyWorkflowServiceGrpc.MutinyWorkflowServiceStub getWorkflowStub() {
        return workflowStub;
    }

    public MutinyExecutorServiceGrpc.MutinyExecutorServiceStub getExecutorStub() {
        return executorStub;
    }

    @Override
    public void close() throws IOException {
        if (channel != null && !channel.isShutdown()) {
            try {
                channel.shutdown();
                // Wait for graceful shutdown
                if (!channel.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)) {
                    channel.shutdownNow();
                    if (!channel.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)) {
                        System.err.println("Channel did not terminate in time");
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                channel.shutdownNow();
            }
        }
    }
}