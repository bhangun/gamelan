# Gamelan SDK Executor - Remote

This module provides a distributed, remote executor implementation for the Gamelan workflow engine. It enables you to run workflow executors (also known as agents or workers) in separate processes or containers, communicating with the Gamelan engine via gRPC or Kafka. This is the production-ready solution for scaling executors across multiple nodes.

## Key Features

- **Multiple Transport Protocols**: Support for both gRPC (synchronous, low-latency) and Kafka (asynchronous, event-driven) communication.
- **Automatic Discovery & Registration**: Executors automatically register with the Gamelan engine on startup and maintain health through heartbeat mechanisms.
- **Distributed Execution**: Scale executors independently across multiple machines, containers, or cloud instances.
- **Load Balancing**: Engine distributes tasks across registered executors with built-in load balancing.
- **JWT-Based Security**: Secure inter-service communication using JWT tokens for authentication.
- **Reactive by Design**: Built on Mutiny for non-blocking, high-performance async operations.
- **Unified API**: Uses the same `AbstractWorkflowExecutor` base class as local executors for code portability.

## Installation

Add the following dependency to your `pom.xml`:

```xml
<dependency>
    <groupId>tech.kayys.gamelan</groupId>
    <artifactId>gamelan-sdk-executor-remote</artifactId>
    <version>${gamelan.version}</version>
</dependency>
```

## Quick Start

### 1. Create Your First Remote Executor

Implement a task executor by extending `AbstractWorkflowExecutor` (same as local):

```java
import tech.kayys.gamelan.sdk.executor.core.AbstractWorkflowExecutor;
import tech.kayys.gamelan.sdk.executor.core.Executor;
import tech.kayys.gamelan.core.scheduler.CommunicationType;
import tech.kayys.gamelan.engine.node.NodeExecutionTask;
import tech.kayys.gamelan.engine.node.NodeExecutionResult;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Map;

@ApplicationScoped
@Executor(
    executorType = "calculate-tax",
    communicationType = CommunicationType.GRPC,
    maxConcurrentTasks = 50
)
public class CalculateTaxExecutor extends AbstractWorkflowExecutor {
    @Override
    public Uni<NodeExecutionResult> execute(NodeExecutionTask task) {
        // Extract input from task context
        Double amount = (Double) task.context().get("amount");
        
        // Perform calculation
        Double tax = amount * 0.1;
        
        // Return success with output data
        return Uni.createFrom().item(
            NodeExecutionResult.success(task, Map.of("tax", tax))
        );
    }
}
```

### 2. Configure the Executor Runtime

Set up application configuration in `application.properties` or `application.yml`:

**application.properties:**
```properties
# Engine Registration
gamelan.executor.engine-url=http://gamelan-engine:8080
gamelan.executor.executor-name=tax-service
gamelan.executor.executor-version=1.0.0

# Transport Configuration
gamelan.executor.transport=grpc
gamelan.executor.grpc.port=9090
gamelan.executor.grpc.host=0.0.0.0

# Security
gamelan.executor.jwt.secret=${JWT_SECRET}
gamelan.executor.jwt.issuer=gamelan-executor

# Heartbeat Settings
gamelan.executor.heartbeat.interval-seconds=30
gamelan.executor.heartbeat.timeout-seconds=60

# Performance
gamelan.executor.thread-pool-size=50
gamelan.executor.queue-size=1000
```

**application.yml:**
```yaml
gamelan:
  executor:
    engine-url: http://gamelan-engine:8080
    executor-name: tax-service
    executor-version: 1.0.0
    transport: grpc  # or 'kafka'
    grpc:
      port: 9090
      host: 0.0.0.0
    kafka:
      bootstrap-servers: kafka:29092
      group-id: executor-group
    jwt:
      secret: ${JWT_SECRET}
      issuer: gamelan-executor
    heartbeat:
      interval-seconds: 30
      timeout-seconds: 60
    thread-pool-size: 50
    queue-size: 1000
```

### 3. Deploy the Executor Service

Create a Docker container for your executor:

```dockerfile
FROM quay.io/quarkus/quarkus-distroless-image:latest
COPY target/quarkus-app/lib/ /deployments/lib/
COPY target/quarkus-app/*.jar /deployments/
COPY target/quarkus-app/app/ /deployments/app/
COPY target/quarkus-app/quarkus/ /deployments/quarkus/
EXPOSE 9090
CMD ["java", "-Dquarkus.http.host=0.0.0.0", "-cp", "/deployments/lib/…:/deployments/app/:…", "io.quarkus.runner.GeneratedMain"]
```

## Architecture

### RemoteExecutorRuntime

The core component that:
- Starts automatically on application startup
- Discovers all `@ApplicationScoped` beans extending `AbstractWorkflowExecutor`
- Registers executors with the Gamelan engine via the chosen transport
- Maintains heartbeat signals to prove liveness to the engine
- Handles connection failure recovery and re-registration
- Manages the thread pool for executing tasks

### Transport Layer

The remote executor supports two transport mechanisms:

#### gRPC Transport (`GrpcExecutorTransport`)
- **Protocol**: Binary RPC over HTTP/2
- **Use Case**: Low-latency, synchronous execution
- **Advantages**: 
  - Sub-millisecond latency
  - Bidirectional streaming support
  - Efficient binary serialization
- **Configuration**:
  ```properties
  gamelan.executor.transport=grpc
  gamelan.executor.grpc.port=9090
  ```

#### Kafka Transport (`KafkaExecutorTransport`)
- **Protocol**: Event streaming via Kafka topics
- **Use Case**: Decoupled, asynchronous execution
- **Advantages**:
  - Decoupled from engine (engine doesn't need executor address)
  - Built-in message persistence and replay
  - Horizontal scaling of consumers
  - Better for long-running tasks
- **Configuration**:
  ```properties
  gamelan.executor.transport=kafka
  gamelan.executor.kafka.bootstrap-servers=kafka:29092
  gamelan.executor.kafka.group-id=tax-executor-group
  ```

### Security Layer

#### JWT Authentication (`JwtClientInterceptor`)
- All remote communication is authenticated using JWT tokens
- Executors must provide valid JWT tokens signed with the configured secret
- Tokens include executor identity and version information
- Support for token expiration and refresh

### Execution Flow

```
┌─────────────────────────────────────────────────────────┐
│ Gamelan Workflow Engine (Central)                       │
│ - Orchestrates workflow execution                       │
│ - Distributes tasks to registered executors             │
└─────────────────────────────────────────────────────────┘
                    ↓
        ┌───────────────────────┐
        │ Transport (gRPC/Kafka)│ (Network)
        └───────────────────────┘
                    ↓
┌─────────────────────────────────────────────────────────┐
│ Executor Service 1 (Remote Process)                     │
│ - Receives task from engine                             │
│ - RemoteExecutorRuntime manages lifecycle               │
│ - Routes to appropriate executor bean                   │
│ - Executes in thread pool                               │
│ - Returns result to engine                              │
└─────────────────────────────────────────────────────────┘

Similar executor services can run on different machines/containers
```

## Advanced Usage

### Multiple Executors Per Service

You can host multiple executor types in a single service:

```java
@ApplicationScoped
@Executor(executorType = "order-validator", communicationType = CommunicationType.GRPC)
public class OrderValidatorExecutor extends AbstractWorkflowExecutor { ... }

@ApplicationScoped
@Executor(executorType = "payment-processor", communicationType = CommunicationType.KAFKA)
public class PaymentProcessorExecutor extends AbstractWorkflowExecutor { ... }

@ApplicationScoped
@Executor(executorType = "notification-sender", communicationType = CommunicationType.GRPC)
public class NotificationExecutor extends AbstractWorkflowExecutor { ... }
```

All three executors will be discovered and registered with the engine automatically.

### Custom Transport Configuration

For advanced gRPC configuration:

```properties
# gRPC Performance Tuning
gamelan.executor.grpc.keep-alive-time-seconds=30
gamelan.executor.grpc.keep-alive-timeout-seconds=10
gamelan.executor.grpc.max-concurrent-streams=1000
gamelan.executor.grpc.flow-control-window=1048576

# TLS/mTLS Support
gamelan.executor.grpc.use-ssl=true
gamelan.executor.grpc.certificate-path=/etc/secrets/executor.crt
gamelan.executor.grpc.private-key-path=/etc/secrets/executor.key
gamelan.executor.grpc.ca-certificate-path=/etc/secrets/ca.crt
```

### Async I/O Operations

Use Mutiny's reactive APIs for optimal performance in distributed scenarios:

```java
@ApplicationScoped
@Executor(executorType = "fetch-external-data")
public class FetchDataExecutor extends AbstractWorkflowExecutor {
    
    @Inject
    ExternalService externalService;
    
    @Override
    public Uni<NodeExecutionResult> execute(NodeExecutionTask task) {
        String dataId = (String) task.context().get("dataId");
        
        return externalService.fetchDataAsync(dataId)
            .timeout().in(30, TimeUnit.SECONDS) // Prevent hanging
            .map(data -> NodeExecutionResult.success(task, 
                Map.of("data", data, "fetchedAt", Instant.now())))
            .onFailure().recoverWithItem(failure -> {
                LOG.error("Failed to fetch data: {}", dataId, failure);
                return NodeExecutionResult.failure(task, 
                    "External service unavailable");
            });
    }
}
```

### Handling Executor Failure and Recovery

The remote executor includes automatic retry and recovery:

```java
@ApplicationScoped
@Executor(executorType = "resilient-task")
public class ResilientExecutor extends AbstractWorkflowExecutor {
    
    private static final Logger LOG = LoggerFactory.getLogger(ResilientExecutor.class);
    private static final int MAX_RETRIES = 3;
    
    @Override
    public Uni<NodeExecutionResult> execute(NodeExecutionTask task) {
        return executeWithRetry(task, 0);
    }
    
    private Uni<NodeExecutionResult> executeWithRetry(NodeExecutionTask task, int attempt) {
        return performWork(task)
            .map(result -> NodeExecutionResult.success(task, result))
            .onFailure().retry()
                .withBackOff(Duration.ofSeconds(1))
                .atMost(MAX_RETRIES)
            .onFailure().recoverWithItem(failure -> 
                NodeExecutionResult.failure(task, "Max retries exceeded"));
    }
    
    private Uni<Map<String, Object>> performWork(NodeExecutionTask task) {
        // Work logic here
        return Uni.createFrom().item(Map.of("status", "done"));
    }
}
```

## Best Practices

1. **Service Separation**: Deploy different executor types in separate services for independent scaling.

2. **Thread Pool Sizing**: Configure `thread-pool-size` based on:
   - Number of cores available
   - I/O vs CPU-bound tasks
   - Expected concurrent task load
   ```properties
   # For I/O-bound: cores * 2-3
   # For CPU-bound: cores
   gamelan.executor.thread-pool-size=100
   ```

3. **Heartbeat Configuration**: 
   - Keep heartbeat interval reasonable (30-60 seconds)
   - Ensure timeout is greater than interval
   - Monitor heartbeat latency in logs

4. **Load Balancing Hints**: Specify `maxConcurrentTasks` to help engine distribute work:
   ```java
   @Executor(
       executorType = "heavy-computation",
       maxConcurrentTasks = 10  // Process only 10 tasks concurrently
   )
   ```

5. **Monitoring & Observability**:
   - Enable structured logging with correlation IDs
   - Export metrics (task count, latency, errors)
   - Monitor registration status and heartbeat health

6. **Version Management**: Keep executor versions in sync with expectations:
   ```properties
   gamelan.executor.executor-version=2.0.0
   ```

7. **Error Handling**: 
   - Distinguish business failures from technical errors
   - Return meaningful error details for debugging
   - Log all failures for troubleshooting

## Comparison: gRPC vs Kafka

| Aspect | gRPC | Kafka |
|--------|------|-------|
| **Protocol** | HTTP/2 Binary RPC | Event Stream |
| **Latency** | <10ms | 100ms-1s |
| **Synchrony** | Request-Response | Asynchronous |
| **Coupling** | Executor address required | Decoupled |
| **Persistence** | None (re-execution required) | Built-in message retention |
| **Scaling** | Stateless instances | Consumer groups |
| **Best For** | Fast, interactive tasks | Batch, long-running, decoupled |

## Deployment Examples

### Docker Compose

```yaml
version: '3.8'
services:
  gamelan-engine:
    image: gamelan-engine:latest
    ports:
      - "8080:8080"
    environment:
      GAMELAN_MODE: distributed

  tax-executor:
    image: tax-executor:latest
    ports:
      - "9090:9090"
    environment:
      GAMELAN_EXECUTOR_ENGINE_URL: http://gamelan-engine:8080
      GAMELAN_EXECUTOR_TRANSPORT: grpc
      GAMELAN_EXECUTOR_NAME: tax-service
    depends_on:
      - gamelan-engine

  payment-executor:
    image: payment-executor:latest
    ports:
      - "9091:9090"
    environment:
      GAMELAN_EXECUTOR_ENGINE_URL: http://gamelan-engine:8080
      GAMELAN_EXECUTOR_TRANSPORT: kafka
      GAMELAN_EXECUTOR_KAFKA_BOOTSTRAP_SERVERS: kafka:29092
    depends_on:
      - gamelan-engine
      - kafka

  kafka:
    image: confluentinc/cp-kafka:latest
    ports:
      - "29092:29092"
```

### Kubernetes Deployment

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: tax-executor
spec:
  replicas: 3
  template:
    spec:
      containers:
      - name: executor
        image: tax-executor:latest
        ports:
        - containerPort: 9090
        env:
        - name: GAMELAN_EXECUTOR_ENGINE_URL
          value: http://gamelan-engine:8080
        - name: GAMELAN_EXECUTOR_TRANSPORT
          value: grpc
        - name: GAMELAN_EXECUTOR_NAME
          value: tax-service
        - name: GAMELAN_EXECUTOR_THREAD_POOL_SIZE
          value: "50"
        resources:
          requests:
            cpu: "1"
            memory: "512Mi"
          limits:
            cpu: "2"
            memory: "1Gi"
        livenessProbe:
          httpGet:
            path: /q/health/live
            port: 8080
          initialDelaySeconds: 30
          periodSeconds: 10
        readinessProbe:
          httpGet:
            path: /q/health/ready
            port: 8080
          initialDelaySeconds: 10
          periodSeconds: 5
```

## Testing

The module includes test examples in `src/test/java/tech/kayys/gamelan/sdk/executor/examples/`:
- `OrderValidatorExecutorTest` - Testing remote executor logic
- `PaymentProcessorExecutor` - Example with async operations
- Integration tests with gRPC and Kafka transports

Run tests:
```bash
mvn test
```

## Documentation

For comprehensive guides on implementing and using agents, see:
- **[AGENTS.md](../gamelan-sdk-executor-local/AGENTS.md)**: Detailed guide for workflow executor implementation
- **[docs/example.md](docs/example.md)**: Working code examples for common patterns
- **[Local Executor README](../gamelan-sdk-executor-local/README.md)**: For comparison and local development

## Troubleshooting

### Executor Registration Fails

**Problem**: Executor not appearing in engine as registered
- Verify engine URL is correct and reachable
- Check network connectivity between executor and engine
- Review logs for JWT token generation errors
- Ensure executor name and version are configured

**Solution**:
```properties
gamelan.executor.engine-url=http://gamelan-engine:8080
gamelan.executor.executor-name=my-service
gamelan.executor.jwt.secret=your-secret-key
```

### Heartbeat Timeout

**Problem**: Engine marks executor as dead
- Increase heartbeat interval if infrastructure is slow
- Check network latency and packet loss
- Verify thread pool isn't completely saturated

**Solution**:
```properties
gamelan.executor.heartbeat.interval-seconds=60
gamelan.executor.heartbeat.timeout-seconds=120
```

### Tasks Not Being Assigned

**Problem**: Engine doesn't send tasks to executor
- Verify executor registered successfully (check logs)
- Ensure executor type matches workflow node type
- Check if executor is at maxConcurrentTasks limit

**Solution**:
```java
@Executor(executorType = "my-exact-task-type", maxConcurrentTasks = 100)
```

### Transport Connection Issues

**For gRPC**:
- Verify gRPC port is open and accessible
- Check for firewall rules blocking port
- Ensure SSL/TLS certificates are valid if enabled

**For Kafka**:
- Verify Kafka bootstrap servers are reachable
- Check Kafka topic creation and permissions
- Verify consumer group ID is correct

### High Task Latency

**Problem**: Tasks execute slowly
- Increase `thread-pool-size` if thread limited
- Check for blocking I/O operations (should be async)
- Monitor CPU and memory usage
- Review task execution logs for bottlenecks

**Solution**:
```properties
gamelan.executor.thread-pool-size=200
gamelan.executor.queue-size=5000
```

## Performance Tuning

### For High Throughput
```properties
# Increase concurrency and queue size
gamelan.executor.thread-pool-size=200
gamelan.executor.queue-size=10000
gamelan.executor.grpc.max-concurrent-streams=1000

# Reduce heartbeat overhead (if stable network)
gamelan.executor.heartbeat.interval-seconds=60
```

### For Low Latency
```properties
# Optimize for single-request latency
gamelan.executor.grpc.keep-alive-time-seconds=10
gamelan.executor.thread-pool-size=50  # Don't over-subscribe
```

### For Resource Constraints
```properties
gamelan.executor.thread-pool-size=10
gamelan.executor.queue-size=100
gamelan.executor.heartbeat.interval-seconds=60
```
