package tech.kayys.gamelan.sdk.executor;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.operators.multi.processors.BroadcastProcessor;
import tech.kayys.gamelan.engine.node.NodeExecutionResult;
import tech.kayys.gamelan.engine.node.NodeExecutionTask;
import tech.kayys.gamelan.engine.protocol.CommunicationType;
import tech.kayys.gamelan.sdk.executor.core.ExecutorTransport;
import tech.kayys.gamelan.sdk.executor.core.WorkflowExecutor;

/**
 * Kafka-based executor transport
 */
@ApplicationScoped
public class KafkaExecutorTransport implements ExecutorTransport {

    private static final Logger LOG = LoggerFactory.getLogger(KafkaExecutorTransport.class);

    private final String executorId;

    @ConfigProperty(name = "kafka.registration.timeout", defaultValue = "30s")
    Duration registrationTimeout;

    @ConfigProperty(name = "heartbeat.interval", defaultValue = "30s")
    Duration heartbeatInterval;

    // For task processing
    private final BroadcastProcessor<NodeExecutionTask> taskProcessor = BroadcastProcessor.create();

    // Kafka producers for different topics
    @Channel("execution-results")
    private Emitter<NodeExecutionResult> resultEmitter;

    @Channel("executor-heartbeats")
    private Emitter<ExecutorHeartbeat> heartbeatEmitter;

    @Channel("executor-registrations")
    private Emitter<ExecutorRegistration> registrationEmitter;

    @Channel("executor-unregistrations")
    private Emitter<ExecutorUnregistration> unregistrationEmitter;

    public KafkaExecutorTransport() {
        this.executorId = UUID.randomUUID().toString();
    }

    @Incoming("workflow-tasks")
    public void consumeTask(NodeExecutionTask task) {
        LOG.debug("Received task: {} from Kafka", task.nodeId());
        taskProcessor.onNext(task);
    }

    @Override
    public CommunicationType getCommunicationType() {
        return CommunicationType.KAFKA;
    }

    @Override
    public Uni<Void> register(List<WorkflowExecutor> executors) {
        LOG.info("Registering {} executors via Kafka", executors.size());

        List<String> executorTypes = executors.stream()
                .map(WorkflowExecutor::getExecutorType)
                .toList();

        ExecutorRegistration registration = new ExecutorRegistration(
                executorId,
                executorTypes,
                System.currentTimeMillis());

        return Uni.createFrom().completionStage(registrationEmitter.send(registration))
                .onItem().invoke(() -> LOG.info("Executor registration message sent: {}", executorId))
                .onFailure().invoke(e -> LOG.error("Failed to send registration message", e))
                .ifNoItem().after(registrationTimeout).fail()
                .replaceWithVoid();
    }

    @Override
    public Uni<Void> unregister() {
        LOG.info("Unregistering via Kafka for executor: {}", executorId);

        ExecutorUnregistration unregistration = new ExecutorUnregistration(
                executorId,
                System.currentTimeMillis());

        return Uni.createFrom().completionStage(unregistrationEmitter.send(unregistration))
                .onItem().invoke(() -> LOG.info("Executor unregistration message sent: {}", executorId))
                .onFailure().invoke(e -> LOG.error("Failed to send unregistration message", e))
                .replaceWithVoid();
    }

    @Override
    public Multi<NodeExecutionTask> receiveTasks() {
        LOG.info("Setting up Kafka task consumer");
        return taskProcessor;
    }

    @Override
    public Uni<Void> sendResult(NodeExecutionResult result) {
        return Uni.createFrom().emitter(emitter -> {
            try {
                // Send result to Kafka
                resultEmitter.send(result);

                LOG.debug("Result sent to Kafka for task: {}", result.getNodeId());
                emitter.complete(null);
            } catch (Exception e) {
                LOG.error("Failed to send result for task: {}", result.getNodeId(), e);
                emitter.fail(e);
            }
        });
    }

    @Override
    public Uni<Void> sendHeartbeat() {
        return Uni.createFrom().emitter(emitter -> {
            try {
                ExecutorHeartbeat heartbeat = new ExecutorHeartbeat(
                        executorId,
                        System.currentTimeMillis());

                // Send heartbeat to Kafka
                heartbeatEmitter.send(heartbeat);

                LOG.trace("Heartbeat sent to Kafka for executor: {}", executorId);
                emitter.complete(null);
            } catch (Exception e) {
                LOG.warn("Failed to send heartbeat for executor: {}", executorId, e);
                emitter.complete(null); // Don't fail for heartbeat issues
            }
        });
    }

    @Override
    public Duration getHeartbeatInterval() {
        return heartbeatInterval;
    }

    /**
     * Helper class for executor registration messages
     */
    public record ExecutorRegistration(String executorId, List<String> supportedTypes, long timestamp) {
    }

    /**
     * Helper class for executor unregistration messages
     */
    public record ExecutorUnregistration(String executorId, long timestamp) {
    }

    /**
     * Helper class for executor heartbeat messages
     */
    public record ExecutorHeartbeat(String executorId, long timestamp) {
    }

    @PreDestroy
    public void cleanup() {
        LOG.info("Cleaning up Kafka transport for executor: {}", executorId);

        // Close the task processor
        taskProcessor.onComplete();
    }
}
