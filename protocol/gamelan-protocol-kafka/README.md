# Gamelan Protocol - Kafka

This module provides **Kafka-based event streaming protocol** for the Gamelan Workflow Engine. It enables asynchronous, decoupled communication between the central workflow engine and distributed executors using Apache Kafka as the message broker, ideal for large-scale, distributed workflow processing with built-in message persistence and replay capabilities.

## Overview

Kafka is a distributed event streaming platform that provides:
- **Asynchronous Communication**: Fire-and-forget messaging without immediate response requirement
- **Message Persistence**: All messages persisted to disk for durability and replay
- **Decoupling**: Engine and executors don't need to know each other's addresses
- **Scalability**: Horizontal scaling through consumer groups and partitioning
- **Event Sourcing**: Natural fit for event-driven architectures and audit trails
- **Fault Tolerance**: Automatic failover and message retention

## Key Features

- **Event-Driven Architecture**: Task distribution and result collection via Kafka topics
- **Topic-Based Communication**: Structured topics for different message types (tasks, results, status)
- **Consumer Groups**: Multiple executors can consume from the same group for load distribution
- **Dead Letter Queues**: Failed messages routed to DLQ for manual handling
- **Message Serialization**: JSON serialization with custom SerDes (Serializers/Deserializers)
- **Event Sourcing**: Complete event history for audit and replay
- **Status Notifications**: Asynchronous status updates via pub-sub messaging
- **Reactive Messaging**: Built on Quarkus SmallRye Reactive Messaging for async operations

## Installation

Add the following dependency to your project:

```xml
<dependency>
    <groupId>tech.kayys.gamelan</groupId>
    <artifactId>gamelan-protocol-kafka</artifactId>
    <version>${gamelan.version}</version>
</dependency>
```

Ensure Kafka is running and accessible:

```bash
# Using Docker Compose
docker-compose up -d kafka zookeeper

# Or with Kraft mode (Kafka 3.0+)
docker-compose -f docker-compose-kraft.yml up -d kafka
```

## Architecture

### Topic Structure

Gamelan uses the following Kafka topics for communication:

```
gamelan.workflow-tasks
├── Partition 0: Tasks for executor-group-1
├── Partition 1: Tasks for executor-group-2
└── Partition N: Tasks for executor-group-N

gamelan.task-results
├── Partition 0: Results from executor-group-1
├── Partition 1: Results from executor-group-2
└── Partition N: Results from executor-group-N

gamelan.workflow-events
├── Event history for workflow runs
└── Partitioned by workflow ID for ordering

gamelan.status-updates
├── Real-time status notifications
└── Partitioned by tenant for multi-tenancy

gamelan.dead-letter-queue
└── Failed messages for manual investigation
```

### Message Flow

```
┌─────────────────────────────────────────────────────────┐
│ Gamelan Workflow Engine                                 │
│ - Orchestrates workflows                                │
│ - Creates/manages runs                                  │
│ - Publishes tasks to Kafka                              │
└─────────────────────────────────────────────────────────┘
                         ↓
              ┌──────────────────────┐
              │   Kafka Broker       │
              │                      │
              │ Topics:              │
              │ - workflow-tasks     │
              │ - task-results       │
              │ - workflow-events    │
              │ - status-updates     │
              │ - dead-letter-queue  │
              └──────────────────────┘
                         ↓
┌─────────────────────────────────────────────────────────┐
│ Executor Service (Remote, Multiple Instances)           │
│ - Subscribes to workflow-tasks topic                    │
│ - Processes tasks independently                         │
│ - Publishes results to task-results topic               │
└─────────────────────────────────────────────────────────┘
```

## Core Components

### 1. Task Distribution

#### KafkaTaskProducer
Publishes task assignments to Kafka topic:

```java
@ApplicationScoped
public class KafkaTaskProducer {
    @Inject
    @Channel("workflow-tasks")
    Emitter<Record<String, TaskMessage>> taskEmitter;
    
    public Uni<Void> sendTask(NodeExecutionTask task, String executor) {
        TaskMessage message = new TaskMessage(...);
        return Uni.createFrom().completionStage(
            taskEmitter.send(Record.of(task.runId().value(), message))
        );
    }
}
```

#### KafkaTaskConsumer
Consumes and processes task assignments:

```java
@ApplicationScoped
public class KafkaTaskConsumer {
    @Inject
    ExecutorTaskHandler taskHandler;
    
    @Incoming("workflow-tasks")
    public Uni<Void> consumeTask(TaskMessage message) {
        return taskHandler.handleTask(message);
    }
}
```

### 2. Result Collection

#### KafkaResultProducer
Publishes task results back to engine:

```java
@ApplicationScoped
public class KafkaResultProducer {
    @Inject
    @Channel("task-results")
    Emitter<Record<String, TaskResultMessage>> resultEmitter;
    
    public Uni<Void> reportResult(TaskResultMessage result) {
        return Uni.createFrom().completionStage(
            resultEmitter.send(Record.of(result.runId(), result))
        );
    }
}
```

#### KafkaResultConsumer
Consumes and processes task results:

```java
@ApplicationScoped
public class KafkaResultConsumer {
    @Incoming("task-results")
    public Uni<Void> consumeResult(TaskResultMessage result) {
        // Process result and update workflow state
        return workflowEngine.handleTaskResult(result);
    }
}
```

### 3. Event Sourcing

#### KafkaEventPublisher
Publishes workflow events for audit trail:

```java
@ApplicationScoped
public class KafkaEventPublisher {
    @Inject
    @Channel("workflow-events")
    Emitter<Record<String, WorkflowEventMessage>> eventEmitter;
    
    public Uni<Void> publishEvent(WorkflowEventMessage event) {
        return Uni.createFrom().completionStage(
            eventEmitter.send(Record.of(event.runId(), event))
        );
    }
}
```

#### KafkaEventConsumer
Consumes events for projections and analytics:

```java
@ApplicationScoped
public class KafkaEventConsumer {
    @Incoming("workflow-events")
    public Uni<Void> consumeEvent(WorkflowEventMessage event) {
        return eventProjectionService.project(event);
    }
}
```

### 4. Status Notifications

#### KafkaStatusPublisher
Publishes workflow status updates:

```java
@ApplicationScoped
public class KafkaStatusPublisher {
    @Inject
    @Channel("status-updates")
    Emitter<Record<String, StatusUpdateMessage>> statusEmitter;
    
    public Uni<Void> publishStatus(StatusUpdateMessage status) {
        return Uni.createFrom().completionStage(
            statusEmitter.send(Record.of(status.tenantId(), status))
        );
    }
}
```

### 5. Dead Letter Handling

#### KafkaDeadLetterHandler
Manages failed messages and retries:

```java
@ApplicationScoped
public class KafkaDeadLetterHandler {
    @Incoming("dead-letter-queue")
    public Uni<Void> handleDeadLetter(DeadLetterMessage message) {
        LOG.error("Dead letter received: {}", message);
        // Store for manual investigation
        return deadLetterStore.save(message);
    }
}
```

## Message Types

### TaskMessage

Represents a task assignment sent to executors:

```java
public class TaskMessage {
    String taskId;                    // Unique task identifier
    String runId;                     // Workflow run ID
    String nodeId;                    // Node being executed
    int attempt;                      // Retry attempt number
    String token;                     // Execution token
    Map<String, Object> context;      // Input data
    String targetExecutor;            // Executor type
    Instant createdAt;                // When task was created
}
```

### TaskResultMessage

Represents task execution result:

```java
public class TaskResultMessage {
    String taskId;                    // Original task ID
    String runId;                     // Workflow run ID
    String nodeId;                    // Executed node
    int attempt;                      // Attempt number
    String token;                     // Execution token
    ExecutionStatus status;           // SUCCESS, FAILURE, TIMEOUT
    Map<String, Object> output;       // Output data
    ErrorInfo error;                  // Error if failed
    Duration executionTime;           // How long execution took
    String executorId;                // Which executor ran it
    Instant completedAt;              // When it completed
}
```

### WorkflowEventMessage

Represents a workflow event for event sourcing:

```java
public class WorkflowEventMessage {
    String eventId;                   // Unique event ID
    String runId;                     // Workflow run ID
    String eventType;                 // RUN_CREATED, NODE_STARTED, NODE_COMPLETED, etc.
    Map<String, Object> payload;      // Event details
    Instant timestamp;                // When event occurred
    String actorId;                   // Who triggered event
}
```

### StatusUpdateMessage

Represents real-time status notification:

```java
public class StatusUpdateMessage {
    String runId;                     // Workflow run ID
    String tenantId;                  // Tenant ID
    String status;                    // RUNNING, COMPLETED, FAILED, etc.
    Map<String, Object> data;         // Status details
    Instant timestamp;                // Status timestamp
}
```

## Configuration

### Application Properties

```properties
# Kafka Bootstrap Configuration
kafka.bootstrap.servers=localhost:9092
kafka.group.id=gamelan-engine

# Topic Configuration
gamelan.kafka.tasks.topic=gamelan.workflow-tasks
gamelan.kafka.results.topic=gamelan.task-results
gamelan.kafka.events.topic=gamelan.workflow-events
gamelan.kafka.status.topic=gamelan.status-updates
gamelan.kafka.dlq.topic=gamelan.dead-letter-queue

# Message Configuration
gamelan.kafka.partitions=3
gamelan.kafka.replication-factor=2
gamelan.kafka.retention-days=30

# Consumer Configuration
gamelan.kafka.consumer.auto-offset-reset=earliest
gamelan.kafka.consumer.enable-auto-commit=true
gamelan.kafka.consumer.max-poll-records=100
gamelan.kafka.consumer.session-timeout-ms=30000

# Producer Configuration
gamelan.kafka.producer.acks=all
gamelan.kafka.producer.compression-type=snappy
gamelan.kafka.producer.linger-ms=100
gamelan.kafka.producer.batch-size=32768

# Error Handling
gamelan.kafka.dlq.enabled=true
gamelan.kafka.dlq.max-retries=3
gamelan.kafka.dlq.backoff-ms=1000
```

### Application YAML

```yaml
gamelan:
  kafka:
    tasks:
      topic: gamelan.workflow-tasks
    results:
      topic: gamelan.task-results
    events:
      topic: gamelan.workflow-events
    status:
      topic: gamelan.status-updates
    dlq:
      topic: gamelan.dead-letter-queue
      enabled: true
      max-retries: 3
    
    partitions: 3
    replication-factor: 2
    retention-days: 30
    
    consumer:
      auto-offset-reset: earliest
      max-poll-records: 100
      session-timeout-ms: 30000
    
    producer:
      acks: all
      compression-type: snappy
      linger-ms: 100
```

## Usage Examples

### Publishing a Task

```java
@Inject
KafkaTaskProducer taskProducer;

public void assignTaskToExecutor(NodeExecutionTask task) {
    taskProducer.sendTask(task, "payment-processor")
        .subscribe().with(
            v -> LOG.info("Task published: {}", task.taskId()),
            failure -> LOG.error("Failed to publish task", failure)
        );
}
```

### Consuming Tasks

```java
@ApplicationScoped
public class PaymentExecutor {
    
    @Inject
    WorkflowEngine engine;
    
    @Incoming("workflow-tasks")
    @Outgoing("task-results")
    public Uni<TaskResultMessage> processTask(TaskMessage message) {
        if (!message.getTargetExecutor().equals("payment-processor")) {
            return Uni.createFrom().nullItem(); // Skip non-matching tasks
        }
        
        return executePaymentLogic(message)
            .map(result -> createResultMessage(message, result));
    }
    
    private Uni<Map<String, Object>> executePaymentLogic(TaskMessage msg) {
        Double amount = (Double) msg.getContext().get("amount");
        return processPayment(amount)
            .map(txId -> Map.of("transactionId", txId));
    }
}
```

### Event Sourcing

```java
@Inject
KafkaEventPublisher eventPublisher;

public void handleWorkflowCompletion(WorkflowRun run) {
    WorkflowEventMessage event = new WorkflowEventMessage(
        UUID.randomUUID().toString(),
        run.id().value(),
        "RUN_COMPLETED",
        Map.of("status", "SUCCESS", "duration", run.duration()),
        Instant.now(),
        "engine"
    );
    
    eventPublisher.publishEvent(event)
        .subscribe().with(
            v -> LOG.info("Event published: {}", event.eventType())
        );
}
```

### Dead Letter Queue Handling

```java
@ApplicationScoped
public class DLQMonitor {
    
    @Incoming("dead-letter-queue")
    public Uni<Void> handleDeadLetter(DeadLetterMessage message) {
        LOG.warn("Message failed permanently: {}", message.originalTopic());
        
        // Alert operations team
        alertingService.sendAlert(
            AlertLevel.ERROR,
            "Dead letter received: " + message.originalTopic()
        );
        
        return storageService.saveDLQ(message);
    }
}
```

## Consumer Groups and Scaling

### Multiple Executor Instances

Deploy multiple executor instances consuming from the same Kafka topic:

```properties
# All instances in same consumer group
kafka.group.id=executor-group-1

# Kafka automatically distributes partitions across instances
# If 3 partitions and 2 instances:
# - Instance 1 processes: partition 0, 1
# - Instance 2 processes: partition 2
```

When you add a new instance:
```
Instance 3 joins
Kafka rebalances:
- Instance 1: partition 0
- Instance 2: partition 1
- Instance 3: partition 2
```

### Consumer Group Configuration

```properties
# Consumer group for executors
kafka.group.id=gamelan-executors
gamelan.kafka.consumer.group-id=gamelan-executors

# Each executor type can have its own group
# gamelan.kafka.consumer.group-id=payment-executors
# gamelan.kafka.consumer.group-id=validation-executors
```

## Best Practices

1. **Topic Partitioning**: Partition by run ID for ordered processing of single workflow:
   ```java
   taskEmitter.send(Record.of(task.runId(), message))  // Run ID as key
   ```

2. **Consumer Groups**: Use separate consumer groups for different purposes:
   ```properties
   # Executors consuming tasks
   gamelan.kafka.consumer.group-id=gamelan-executors
   
   # Event processors consuming events
   gamelan.kafka.consumer.group-id=gamelan-event-processors
   ```

3. **Error Handling**: Implement dead letter queues for failed messages:
   ```properties
   gamelan.kafka.dlq.enabled=true
   gamelan.kafka.dlq.max-retries=3
   ```

4. **Message Retention**: Balance storage with replay capability:
   ```properties
   gamelan.kafka.retention-days=30  # 30 days for replay
   ```

5. **Compression**: Enable compression for large messages:
   ```properties
   gamelan.kafka.producer.compression-type=snappy
   ```

6. **Batch Processing**: Configure batching for efficiency:
   ```properties
   gamelan.kafka.producer.linger-ms=100
   gamelan.kafka.producer.batch-size=32768
   ```

7. **Monitoring**: Track consumer lag to detect bottlenecks:
   ```bash
   # Monitor consumer lag
   kafka-consumer-groups --bootstrap-server localhost:9092 \
     --group gamelan-executors --describe
   ```

8. **Replication**: Set replication-factor for durability:
   ```properties
   gamelan.kafka.replication-factor=2  # Minimum for HA
   ```

## Kafka Topic Setup

### Create Topics Manually

```bash
# Create task topic
kafka-topics --create \
  --bootstrap-server localhost:9092 \
  --topic gamelan.workflow-tasks \
  --partitions 3 \
  --replication-factor 2 \
  --config retention.ms=2592000000  # 30 days

# Create results topic
kafka-topics --create \
  --bootstrap-server localhost:9092 \
  --topic gamelan.task-results \
  --partitions 3 \
  --replication-factor 2

# Create DLQ topic
kafka-topics --create \
  --bootstrap-server localhost:9092 \
  --topic gamelan.dead-letter-queue \
  --partitions 3 \
  --replication-factor 2 \
  --config retention.ms=2592000000
```

### Automatic Topic Creation

Enable auto-creation in Kafka broker configuration:

```properties
auto.create.topics.enable=true
num.partitions=3
default.replication.factor=2
```

## Performance Tuning

### High Throughput Configuration

```properties
# Increase batch size for throughput
gamelan.kafka.producer.batch-size=65536
gamelan.kafka.producer.linger-ms=200

# Increase consumer parallelism
gamelan.kafka.consumer.max-poll-records=500
gamelan.kafka.partitions=10  # More partitions for parallelism

# Enable compression
gamelan.kafka.producer.compression-type=snappy
```

### Low Latency Configuration

```properties
# Minimize batching
gamelan.kafka.producer.batch-size=1024
gamelan.kafka.producer.linger-ms=1

# Fetch quickly
gamelan.kafka.consumer.fetch.min.bytes=1

# Increase parallelism but keep small batches
gamelan.kafka.partitions=5
```

## Troubleshooting

### Messages Not Being Consumed

**Problem**: Consumer lag increasing or no progress
- Check consumer group status
- Verify topic exists and has messages
- Review consumer logs for errors

**Solution**:
```bash
# Check consumer group
kafka-consumer-groups --bootstrap-server localhost:9092 \
  --group gamelan-executors --describe

# Reset consumer offset
kafka-consumer-groups --bootstrap-server localhost:9092 \
  --group gamelan-executors \
  --reset-offsets --to-earliest --execute \
  --topic gamelan.workflow-tasks
```

### Dead Letter Queue Accumulating

**Problem**: Too many messages in DLQ
- Check error logs for patterns
- Verify executor health
- Review message format compatibility

**Solution**:
```java
// Investigate DLQ messages
@Incoming("dead-letter-queue")
public Uni<Void> analyze(DeadLetterMessage msg) {
    LOG.error("DLQ: originalTopic={}, error={}, payload={}",
        msg.getOriginalTopic(),
        msg.getCause(),
        msg.getPayload());
    return Uni.createFrom().voidItem();
}
```

### Consumer Lag Growing

**Problem**: Executors falling behind task publishing
- Increase number of executor instances
- Increase number of partitions
- Optimize executor processing time

**Solution**:
```bash
# Add more partitions
kafka-topics --alter --bootstrap-server localhost:9092 \
  --topic gamelan.workflow-tasks \
  --partitions 6

# Scale executor instances
kubectl scale deployment tax-executor --replicas=5
```

## Integration with Remote Executors

The Kafka protocol is used by `gamelan-sdk-executor-remote` for Kafka transport. Executors:

1. Import `gamelan-protocol-kafka`
2. Configure Kafka bootstrap servers
3. Subscribe to `gamelan.workflow-tasks` topic
4. Process incoming `TaskMessage` instances
5. Publish results to `gamelan.task-results` topic

## Comparison: gRPC vs Kafka

| Aspect | gRPC | Kafka |
|--------|------|-------|
| **Latency** | <10ms | 100ms-1s |
| **Synchrony** | Synchronous (request-response) | Asynchronous (fire-and-forget) |
| **Coupling** | Executor address required | Completely decoupled |
| **Persistence** | None (lose on restart) | Persisted to disk |
| **Scalability** | Limited by connections | Horizontal via consumer groups |
| **Message Ordering** | Per connection | Per partition |
| **Best For** | Real-time, fast tasks | Batch, long-running, decoupled |

## See Also

- **[Gamelan Protocol - gRPC](../gamelan-protocol-grpc/README.md)**: For synchronous request-response communication
- **[Remote Executor SDK](../../sdk/gamelan-sdk-executor-remote/README.md)**: Uses this protocol for Kafka transport
- **[Kafka Documentation](https://kafka.apache.org/documentation/)**: Official Kafka docs
- **[SmallRye Reactive Messaging](https://smallrye.io/smallrye-reactive-messaging/)**: Quarkus messaging library used
