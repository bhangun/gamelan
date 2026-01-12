package tech.kayys.silat.dispatcher;

import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonObject;
import io.vertx.mutiny.core.Vertx;
import io.vertx.mutiny.kafka.client.producer.KafkaProducer;
import io.vertx.mutiny.kafka.client.producer.KafkaProducerRecord;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tech.kayys.silat.execution.NodeExecutionTask;
import tech.kayys.silat.model.ExecutorInfo;
import tech.kayys.silat.scheduler.TaskMessage;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

@ApplicationScoped
public class KafkaTaskDispatcher {

    private static final Logger LOG = LoggerFactory.getLogger(KafkaTaskDispatcher.class);

    // Kafka configuration properties
    @Inject
    @ConfigProperty(name = "kafka.bootstrap.servers", defaultValue = "localhost:9092")
    String bootstrapServers;

    @Inject
    @ConfigProperty(name = "kafka.task.topic", defaultValue = "workflow.tasks")
    String taskTopic;

    @Inject
    @ConfigProperty(name = "kafka.acks", defaultValue = "all")
    String acks;

    @Inject
    @ConfigProperty(name = "kafka.retries", defaultValue = "3")
    int retries;

    @Inject
    @ConfigProperty(name = "kafka.batch.size", defaultValue = "16384")
    int batchSize;

    @Inject
    @ConfigProperty(name = "kafka.linger.ms", defaultValue = "1")
    int lingerMs;

    @Inject
    @ConfigProperty(name = "kafka.buffer.memory", defaultValue = "33554432")
    long bufferMemory;

    @Inject
    @ConfigProperty(name = "kafka.max.request.size", defaultValue = "1048576")
    int maxRequestSize;

    @Inject
    @ConfigProperty(name = "kafka.compression.type", defaultValue = "snappy")
    String compressionType;

    @Inject
    @ConfigProperty(name = "kafka.request.timeout.ms", defaultValue = "30000")
    int requestTimeoutMs;

    @Inject
    @ConfigProperty(name = "kafka.delivery.timeout.ms", defaultValue = "120000")
    int deliveryTimeoutMs;

    // Retry configuration
    private static final int DEFAULT_MAX_RETRIES = 3;
    private static final long DEFAULT_RETRY_DELAY_MS = 1000;
    private static final long MAX_RETRY_DELAY_MS = 30000; // 30 seconds max

    // Validation limits
    private static final int MAX_IDENTIFIER_LENGTH = 255;
    private static final int MIN_TOKEN_LENGTH = 10;
    private static final int MAX_TOKEN_LENGTH = 512;
    private static final int MAX_MESSAGE_SIZE_BYTES = 1048576; // 1MB

    @Inject
    Vertx vertx;

    private KafkaProducer<String, String> kafkaProducer;

    @PostConstruct
    public void initializeKafkaProducer() {
        Map<String, String> props = new HashMap<>();
        props.put("bootstrap.servers", bootstrapServers);
        props.put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer");
        props.put("value.serializer", "org.apache.kafka.common.serialization.StringSerializer");
        props.put("acks", acks);
        props.put("retries", String.valueOf(retries));
        props.put("batch.size", String.valueOf(batchSize));
        props.put("linger.ms", String.valueOf(lingerMs));
        props.put("buffer.memory", String.valueOf(bufferMemory));
        props.put("max.request.size", String.valueOf(maxRequestSize));
        props.put("compression.type", compressionType);
        props.put("request.timeout.ms", String.valueOf(requestTimeoutMs));
        props.put("delivery.timeout.ms", String.valueOf(deliveryTimeoutMs));
        props.put("max.block.ms", "5000"); // Prevent blocking indefinitely

        this.kafkaProducer = KafkaProducer.create(vertx, props);
    }

    public Uni<Void> dispatch(NodeExecutionTask task, ExecutorInfo executor) {
        long startTime = System.currentTimeMillis();
        String runId = task != null && task.runId() != null ? task.runId().value() : "unknown";
        String nodeId = task != null && task.nodeId() != null ? task.nodeId().value() : "unknown";

        LOG.info("Dispatching task via Kafka to topic: {} for run: {} node: {}",
                taskTopic, runId, nodeId);

        // Validate inputs using reactive approach
        Uni<NodeExecutionTask> validatedTaskUni = validateAndSanitizeTask(task)
                .onFailure().invoke(throwable -> LOG.error("Task validation failed for run: {}", runId, throwable));

        return validatedTaskUni
                .<Void>flatMap(validTask -> validateAndSanitizeExecutor(executor)
                        .onFailure()
                        .invoke(throwable -> LOG.error("Executor validation failed for run: {}", runId, throwable))
                        .<Void>flatMap(validExecutor -> {
                            // Serialize task to Kafka message
                            TaskMessage message = new TaskMessage(
                                    validTask.runId().value(),
                                    validTask.nodeId().value(),
                                    validTask.attempt(),
                                    validTask.token().value(),
                                    validTask.context(),
                                    validExecutor.executorId());

                            // Convert to JSON string
                            String messageJson = sanitizeJsonString(JsonObject.mapFrom(message).encode());

                            // Validate message size to prevent oversized messages
                            int messageSize = messageJson.getBytes().length;
                            if (messageSize > maxRequestSize || messageSize > MAX_MESSAGE_SIZE_BYTES) {
                                LOG.error("Message size exceeds maximum allowed size: {} bytes (max: {})",
                                        messageSize, Math.min(maxRequestSize, MAX_MESSAGE_SIZE_BYTES));
                                return Uni.createFrom()
                                        .<Void>failure(new IllegalArgumentException("Message too large"));
                            }

                            // Create Kafka record
                            KafkaProducerRecord<String, String> record = KafkaProducerRecord.create(taskTopic,
                                    messageJson);

                            // Send to Kafka with retry mechanism provided by Mutiny
                            return kafkaProducer.send(record)
                                    .onFailure().retry()
                                    .withBackOff(Duration.ofMillis(DEFAULT_RETRY_DELAY_MS),
                                            Duration.ofMillis(MAX_RETRY_DELAY_MS))
                                    .atMost(DEFAULT_MAX_RETRIES)
                                    .onItem().invoke(metadata -> {
                                        long duration = System.currentTimeMillis() - startTime;
                                        LOG.info(
                                                "Successfully dispatched task to Kafka - run: {}, node: {}, duration: {}ms, size: {} bytes",
                                                validTask.runId().value(), validTask.nodeId().value(), duration,
                                                messageSize);
                                        LOG.debug("Message details - topic: {}, partition: {}, offset: {}",
                                                metadata.getTopic(), metadata.getPartition(), metadata.getOffset());
                                    })
                                    .onFailure().invoke(throwable -> {
                                        long duration = System.currentTimeMillis() - startTime;
                                        LOG.error(
                                                "Failed to dispatch task after all retries - run: {}, node: {}, duration: {}ms",
                                                validTask.runId().value(), validTask.nodeId().value(), duration,
                                                throwable);
                                    })
                                    .replaceWithVoid();
                        }))
                .onFailure().invoke(throwable -> {
                    long duration = System.currentTimeMillis() - startTime;
                    LOG.error("Task dispatch failed due to validation - run: {}, duration: {}ms", runId, duration,
                            throwable);
                })
                .onFailure().recoverWithUni(failure -> {
                    // Check if we already logged it (we did in previous invokes), but this is a
                    // catch-all
                    return Uni.createFrom().<Void>failure(failure);
                });
    }

    /**
     * Validate and sanitize NodeExecutionTask
     */
    private Uni<NodeExecutionTask> validateAndSanitizeTask(NodeExecutionTask task) {
        if (task == null) {
            LOG.error("Task cannot be null");
            return Uni.createFrom().failure(new IllegalArgumentException("Task cannot be null"));
        }

        if (task.runId() == null || task.runId().value() == null || task.runId().value().trim().isEmpty()) {
            LOG.error("Invalid run ID in task");
            return Uni.createFrom().failure(new IllegalArgumentException("Run ID cannot be null or empty"));
        }

        if (task.nodeId() == null || task.nodeId().value() == null || task.nodeId().value().trim().isEmpty()) {
            LOG.error("Invalid node ID in task");
            return Uni.createFrom().failure(new IllegalArgumentException("Node ID cannot be null or empty"));
        }

        if (task.token() == null || task.token().value() == null || task.token().value().trim().isEmpty()) {
            LOG.error("Invalid token in task");
            return Uni.createFrom().failure(new IllegalArgumentException("Token cannot be null or empty"));
        }

        // Sanitize run ID
        String sanitizedRunId = task.runId().value().trim();
        if (!isValidIdentifier(sanitizedRunId)) {
            LOG.error("Invalid run ID format: {}", sanitizedRunId);
            return Uni.createFrom().failure(new IllegalArgumentException("Invalid run ID format"));
        }

        // Sanitize node ID
        String sanitizedNodeId = task.nodeId().value().trim();
        if (!isValidIdentifier(sanitizedNodeId)) {
            LOG.error("Invalid node ID format: {}", sanitizedNodeId);
            return Uni.createFrom().failure(new IllegalArgumentException("Invalid node ID format"));
        }

        // Sanitize token
        String sanitizedToken = task.token().value().trim();
        if (!isValidToken(sanitizedToken)) {
            LOG.error("Invalid token format: {}", sanitizedToken);
            return Uni.createFrom().failure(new IllegalArgumentException("Invalid token format"));
        }

        return Uni.createFrom().item(task);
    }

    /**
     * Validate and sanitize ExecutorInfo
     */
    private Uni<ExecutorInfo> validateAndSanitizeExecutor(ExecutorInfo executor) {
        if (executor == null) {
            LOG.error("Executor cannot be null");
            return Uni.createFrom().failure(new IllegalArgumentException("Executor cannot be null"));
        }

        if (executor.executorId() == null || executor.executorId().trim().isEmpty()) {
            LOG.error("Invalid executor ID in executor info");
            return Uni.createFrom().failure(new IllegalArgumentException("Executor ID cannot be null or empty"));
        }

        // Sanitize executor ID
        String sanitizedExecutorId = executor.executorId().trim();
        if (!isValidIdentifier(sanitizedExecutorId)) {
            LOG.error("Invalid executor ID format: {}", sanitizedExecutorId);
            return Uni.createFrom().failure(new IllegalArgumentException("Invalid executor ID format"));
        }

        return Uni.createFrom().item(executor);
    }

    /**
     * Validate identifier format (alphanumeric, underscore, hyphen)
     */
    private boolean isValidIdentifier(String identifier) {
        return identifier != null && identifier.matches("^[a-zA-Z0-9_-]+$")
                && identifier.length() <= MAX_IDENTIFIER_LENGTH;
    }

    /**
     * Validate token format (alphanumeric, common token characters)
     */
    private boolean isValidToken(String token) {
        return token != null && token.matches("^[a-zA-Z0-9+/=\\-_:.]+$") &&
                token.length() >= MIN_TOKEN_LENGTH && token.length() <= MAX_TOKEN_LENGTH;
    }

    /**
     * Sanitize JSON string to prevent injection
     */
    private String sanitizeJsonString(String jsonString) {
        // In a real implementation, you might want to validate the JSON structure
        // For now, we'll just return the string as-is since JsonObject.mapFrom() should
        // handle this
        return jsonString;
    }

    public void close() {
        if (kafkaProducer != null) {
            kafkaProducer.closeAndForget();
        }
    }
}