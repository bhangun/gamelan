---
name: register-executor
description: Register workflow executors (local, remote gRPC, Kafka) that perform workflow node tasks
metadata:
  short-description: Register task executors
  category: executors
  difficulty: beginner
---

# Register Executor Skill

Register and configure executors that perform individual workflow node tasks. Support local, gRPC, and Kafka-based execution.

## When to Use

- You need to register service endpoints for workflow tasks
- You want to execute tasks locally or remotely
- You need gRPC or Kafka-based task execution
- You want to manage executor health and availability

## Executor Types

### 1. Local Executor
In-process task execution within the same JVM.

### 2. Remote gRPC Executor
High-performance RPC for distributed task execution.

### 3. Kafka Executor
Async event-driven task execution via Kafka topics.

### 4. Custom Executor
Plugin-based extensible executors.

## Steps

### 1. Create Executor Implementation

#### Local Executor

```java
import tech.kayys.gamelan.executor.local.LocalTaskExecutor;

public class OrderValidatorExecutor extends LocalTaskExecutor {
  
  @Override
  public TaskResult execute(TaskRequest request) {
    String orderId = request.getInput("orderId");
    
    // Validate order
    OrderValidationResult result = validateOrder(orderId);
    
    return TaskResult.builder()
      .nodeId(request.getNodeId())
      .status(TaskStatus.SUCCESS)
      .output(Map.of(
        "isValid", result.isValid(),
        "total", result.getTotal()
      ))
      .build();
  }
  
  private OrderValidationResult validateOrder(String orderId) {
    // Validation logic
    return new OrderValidationResult(true, 99.99);
  }
}
```

#### Remote gRPC Executor

```bash
# Create gRPC service
protoc --java_out=src/main/java \
       --grpc-java_out=src/main/java \
       payment.proto
```

```protobuf
service PaymentService {
  rpc Charge(ChargeRequest) returns (ChargeResponse);
  rpc Refund(RefundRequest) returns (RefundResponse);
}
```

```java
public class PaymentGrpcExecutor implements GrpcTaskExecutor {
  
  private PaymentServiceGrpc.PaymentServiceBlockingStub stub;
  
  public PaymentGrpcExecutor(String endpoint) {
    ManagedChannel channel = ManagedChannelBuilder
      .forTarget(endpoint)
      .usePlaintext()
      .build();
    this.stub = PaymentServiceGrpc.newBlockingStub(channel);
  }
  
  @Override
  public TaskResult execute(TaskRequest request) {
    ChargeRequest chargeReq = ChargeRequest.newBuilder()
      .setAmount((double) request.getInput("amount"))
      .setCustomerId((String) request.getInput("customerId"))
      .build();
    
    ChargeResponse response = stub.charge(chargeReq);
    
    return TaskResult.builder()
      .nodeId(request.getNodeId())
      .status(response.getSuccess() ? 
        TaskStatus.SUCCESS : TaskStatus.FAILED)
      .output(Map.of(
        "transactionId", response.getTransactionId()
      ))
      .build();
  }
}
```

#### Kafka Executor

```java
public class ShippingKafkaExecutor implements KafkaTaskExecutor {
  
  @Inject
  @Channel("shipping-tasks")
  Emitter<ShippingTask> emitter;
  
  @Override
  public TaskResult execute(TaskRequest request) {
    ShippingTask task = new ShippingTask(
      request.getNodeId(),
      (String) request.getInput("orderId"),
      (String) request.getInput("address")
    );
    
    emitter.send(task);
    
    return TaskResult.builder()
      .nodeId(request.getNodeId())
      .status(TaskStatus.PENDING)
      .output(Map.of("taskId", task.getId()))
      .build();
  }
}
```

### 2. Register Executor Programmatically

```java
@Inject
ExecutorRegistry registry;

// Register local executor
registry.register(
  ExecutorMetadata.builder()
    .id("order-validator")
    .name("Order Validator")
    .type(ExecutorType.LOCAL)
    .executor(new OrderValidatorExecutor())
    .build()
);

// Register gRPC executor
registry.register(
  ExecutorMetadata.builder()
    .id("payment-service")
    .name("Payment Service")
    .type(ExecutorType.GRPC)
    .endpoint("payment-service:50051")
    .build()
);

// Register Kafka executor
registry.register(
  ExecutorMetadata.builder()
    .id("shipping-service")
    .name("Shipping Service")
    .type(ExecutorType.KAFKA)
    .topicName("shipping-tasks")
    .build()
);
```

### 3. Register via Configuration

```yaml
# application.properties
gamelan.executors.enabled=true

gamelan.executor.order-validator.type=local
gamelan.executor.order-validator.class=com.example.OrderValidatorExecutor

gamelan.executor.payment-service.type=grpc
gamelan.executor.payment-service.endpoint=payment-service:50051
gamelan.executor.payment-service.timeout=30s

gamelan.executor.shipping-service.type=kafka
gamelan.executor.shipping-service.topic=shipping-tasks
```

### 4. Configure Executor Options

```java
ExecutorConfig config = ExecutorConfig.builder()
  .timeout(Duration.ofSeconds(30))
  .maxRetries(3)
  .retryBackoffMs(1000)
  .circuitBreaker(CircuitBreakerConfig.builder()
    .failureThreshold(5)
    .timeout(Duration.ofMinutes(1))
    .build())
  .build();

registry.register(
  ExecutorMetadata.builder()
    .id("payment-service")
    .endpoint("payment-service:50051")
    .config(config)
    .build()
);
```

### 5. Implement Health Checks

```java
public class PaymentServiceExecutor implements GrpcTaskExecutor {
  
  @Override
  public HealthStatus health() {
    try {
      HealthCheckRequest req = HealthCheckRequest.newBuilder()
        .setService("PaymentService")
        .build();
      
      HealthCheckResponse response = stub.check(req);
      
      return HealthStatus.builder()
        .healthy(response.getStatus() == ServingStatus.SERVING)
        .timestamp(Instant.now())
        .message(response.getStatus().toString())
        .build();
    } catch (Exception e) {
      return HealthStatus.builder()
        .healthy(false)
        .message(e.getMessage())
        .build();
    }
  }
}
```

### 6. Configure Service Discovery

```yaml
# Consul-based service discovery
gamelan.executor.discovery.enabled=true
gamelan.executor.discovery.type=consul
gamelan.executor.discovery.consul.endpoint=consul:8500

gamelan.executor.auto-register.enabled=true
gamelan.executor.auto-register.ttl=30s
```

```java
@Inject
ConsulRegistry consulRegistry;

// Auto-register local executor with Consul
consulRegistry.register(
  ServiceRegistration.builder()
    .name("order-validator")
    .port(9000)
    .healthCheck(HealthCheck.http(
      "http://localhost:9000/health",
      Duration.ofSeconds(10)
    ))
    .build()
);
```

### 7. Monitor Executor Status

```java
@Inject
ExecutorRegistry registry;

// Get executor status
ExecutorStatus status = registry.getStatus("payment-service");

System.out.println("Healthy: " + status.isHealthy());
System.out.println("Response Time: " + status.getAvgResponseTime());
System.out.println("Error Rate: " + status.getErrorRate());
System.out.println("Last Check: " + status.getLastHealthCheck());
```

## Executor Best Practices

### Retry Configuration

```java
// Exponential backoff
RetryPolicy policy = RetryPolicy.builder()
  .maxRetries(3)
  .backoffStrategy(BackoffStrategy.EXPONENTIAL)
  .initialBackoffMs(1000)
  .maxBackoffMs(30000)
  .multiplier(2.0)
  .build();
```

### Circuit Breaker Pattern

```java
CircuitBreakerConfig config = CircuitBreakerConfig.builder()
  .failureThreshold(5)           // Fail after 5 errors
  .timeout(Duration.ofMinutes(1)) // Try again after 1 min
  .halfOpenRequests(3)            // Allow 3 requests in half-open
  .build();
```

### Timeout Handling

```java
ExecutorConfig config = ExecutorConfig.builder()
  .timeout(Duration.ofSeconds(30))
  .timeoutAction(TimeoutAction.FAIL)  // or RETRY, COMPENSATE
  .build();
```

## Executor Input/Output

### Define Input Schema

```java
executor.setInputSchema(Schema.builder()
  .field("orderId", FieldType.STRING, true)
  .field("amount", FieldType.DOUBLE, true)
  .field("currency", FieldType.STRING, false)
  .build()
);
```

### Define Output Schema

```java
executor.setOutputSchema(Schema.builder()
  .field("success", FieldType.BOOLEAN, true)
  .field("transactionId", FieldType.STRING, true)
  .field("timestamp", FieldType.INSTANT, true)
  .build()
);
```

## Testing Executors

```java
@Test
void testOrderValidatorExecutor() {
  OrderValidatorExecutor executor = new OrderValidatorExecutor();
  
  TaskRequest request = TaskRequest.builder()
    .nodeId("validate-order")
    .input(Map.of("orderId", "ORD-123"))
    .build();
  
  TaskResult result = executor.execute(request);
  
  assertEquals(TaskStatus.SUCCESS, result.getStatus());
  assertTrue((boolean) result.getOutput("isValid"));
}
```

## Troubleshooting

### Executor Not Found
```
Error: Executor 'payment-service' not registered
Fix: Register executor before workflow execution
```

### Connection Timeout
```
Error: gRPC connection timeout
Fix: Verify endpoint, check network connectivity
```

### Health Check Failing
```
Error: Executor unhealthy
Fix: Check executor service, review logs
```

## See Also

- [Define Workflow](./define-workflow.md)
- [Execute Workflow](./execute-workflow.md)
- [Service Discovery](../references/service-discovery.md)
- [Executor SDK](../references/executor-sdk.md)
