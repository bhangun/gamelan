# Gamelan Runtime Core

This module provides **core runtime abstractions and utilities** that are shared across the Gamelan platform. It includes common interfaces, base classes, and utilities used by both the workflow engine and executor runtimes.

## Overview

The Runtime Core module serves as a foundational layer containing:
- Common domain value objects and entities
- Runtime interfaces and abstractions
- Utility classes and helpers
- Common configuration patterns
- Base exception and error handling
- Shared data structures

## Key Features

- **Domain Models**: Immutable value objects and aggregates
- **Runtime Abstractions**: Common runtime interfaces
- **Utilities**: Shared helper classes and functions
- **Error Handling**: Consistent exception types
- **Configuration**: Common configuration interfaces
- **Serialization**: Common serialization utilities

## Installation

Add the following dependency to your project:

```xml
<dependency>
    <groupId>tech.kayys.gamelan</groupId>
    <artifactId>gamelan-runtime-core</artifactId>
    <version>${gamelan.version}</version>
</dependency>
```

## Core Components

### 1. Domain Value Objects

Strongly-typed value objects for identifiers and values:

```java
// Unique identifiers
public class WorkflowRunId { ... }
public class WorkflowDefinitionId { ... }
public class NodeId { ... }
public class TenantId { ... }
public class ExecutionToken { ... }

// Core values
public class ExecutionStatus { ... }
public class RunStatus { ... }
public class NodeExecutionStatus { ... }
```

Usage:
```java
// Type-safe identifiers
WorkflowRunId runId = WorkflowRunId.of("run-123");
NodeId nodeId = NodeId.of("process-payment");

// Prevents mixing up IDs
// runId.equals(nodeId) -> false, compile error if wrong type
```

### 2. Execution Entities

Core execution model entities:

```java
// Execution context
public class NodeExecutionTask {
    WorkflowRunId runId;
    NodeId nodeId;
    Map<String, Object> context;  // Input data
    String token;
    int attempt;
}

// Execution result
public class NodeExecutionResult {
    ExecutionStatus status;
    Map<String, Object> output;    // Output data
    ErrorInfo error;
    Duration executionTime;
    
    public static NodeExecutionResult success(
        NodeExecutionTask task, 
        Map<String, Object> output) { ... }
    
    public static NodeExecutionResult failure(
        NodeExecutionTask task, 
        String errorMessage) { ... }
}
```

### 3. Error Information

Structured error representation:

```java
public class ErrorInfo {
    String errorCode;           // Machine-readable code
    String errorMessage;        // Human-readable message
    String stackTrace;          // Call stack for debugging
    Map<String, Object> context; // Additional context
    
    // Builder
    ErrorInfo error = ErrorInfo.builder()
        .errorCode("INSUFFICIENT_FUNDS")
        .errorMessage("Account balance too low")
        .context(Map.of("amount", 500, "balance", 100))
        .build();
}
```

### 4. Common Exceptions

Exception types used throughout platform:

```java
// Base exception
public class GamelanException extends RuntimeException { ... }

// Specific exceptions
public class WorkflowNotFoundException extends GamelanException { ... }
public class ExecutorNotFoundException extends GamelanException { ... }
public class ConfigurationException extends GamelanException { ... }
public class InvalidExecutionStateException extends GamelanException { ... }
public class TaskDispatchException extends GamelanException { ... }
```

### 5. Runtime Interfaces

Common runtime abstractions:

```java
// Runtime lifecycle
public interface Runtime {
    void start();
    void stop();
    boolean isRunning();
    RuntimeStatus getStatus();
}

// Runtime status
public enum RuntimeStatus {
    INITIALIZING,
    READY,
    PROCESSING,
    FAILED,
    SHUTDOWN
}
```

### 6. Serialization Utilities

Common serialization helpers:

```java
// JSON serialization
public class JsonSerializer {
    public static String toJson(Object obj) { ... }
    public static <T> T fromJson(String json, Class<T> type) { ... }
}

// Protobuf utilities
public class ProtobufUtils {
    public static Struct mapToStruct(Map<String, Object> map) { ... }
    public static Map<String, Object> structToMap(Struct struct) { ... }
}
```

## Common Patterns

### Creating Value Objects

```java
// Creating identifiers
WorkflowRunId runId = WorkflowRunId.of(UUID.randomUUID().toString());
NodeId nodeId = NodeId.of("approve-payment");
TenantId tenantId = TenantId.of("acme-corp");

// IDs are immutable and hashable
Set<WorkflowRunId> runIds = new HashSet<>();
runIds.add(runId);  // Safe to use in collections

// IDs are comparable
if (runId1.equals(runId2)) {
    // Same run
}
```

### Building Execution Tasks

```java
// Create task
NodeExecutionTask task = NodeExecutionTask.builder()
    .runId(WorkflowRunId.of("run-123"))
    .nodeId(NodeId.of("validate-order"))
    .context(Map.of(
        "orderId", "ORD-001",
        "amount", 100.0
    ))
    .token("exec-token-xyz")
    .attempt(1)
    .build();

// Access task data
String orderId = (String) task.context().get("orderId");
```

### Creating Results

```java
// Success result
NodeExecutionResult success = NodeExecutionResult.success(
    task,
    Map.of("validated", true, "timestamp", Instant.now())
);

// Failure result
NodeExecutionResult failure = NodeExecutionResult.failure(
    task,
    "Order ID is invalid",
    Map.of("orderId", "INVALID")
);

// Access result
if (result.isSuccess()) {
    Map<String, Object> output = result.getOutput();
} else {
    ErrorInfo error = result.getError();
}
```

## Configuration Patterns

### Using Configuration

```java
// Access configuration
@Inject
Configuration config;

// Get values with defaults
String timeout = config.get("execution.timeout", "300");
Integer maxRetries = config.getInt("executor.max-retries", 3);
Boolean metricsEnabled = config.getBoolean("metrics.enabled", true);
```

### Configuration Sources

Configuration is loaded from multiple sources (in order):
1. System properties (`-Dkey=value`)
2. Environment variables
3. `application.properties`
4. `application-{profile}.properties`
5. Plugin-provided configuration

## Type Conversions

### Map-Based Data Exchange

```java
// Serialize to map
Map<String, Object> data = Map.of(
    "orderId", "ORD-001",
    "items", List.of("item1", "item2"),
    "metadata", Map.of("source", "API")
);

// Deserialize from map
String orderId = (String) data.get("orderId");
List<?> items = (List<?>) data.get("items");

// Type-safe extraction with defaults
String orderId = (String) data.getOrDefault("orderId", "UNKNOWN");
```

### JSON Serialization

```java
// Serialize object to JSON
String json = JsonSerializer.toJson(executionResult);

// Deserialize JSON to object
NodeExecutionResult result = JsonSerializer.fromJson(
    json, 
    NodeExecutionResult.class
);
```

## Best Practices

1. **Use Value Objects**: Always use typed identifiers
   ```java
   // Good
   WorkflowRunId runId = WorkflowRunId.of("123");
   
   // Avoid
   String runId = "123";
   ```

2. **Immutability**: Keep runtime objects immutable
   ```java
   // Immutable result
   NodeExecutionResult result = NodeExecutionResult.success(task, output);
   
   // Not: mutable result.setStatus(...)
   ```

3. **Null Safety**: Use Optional or null checks
   ```java
   // Safe access
   Optional<String> value = Optional.ofNullable(
       data.get("optional-key")
   );
   
   // With default
   String value = (String) data.getOrDefault("key", "default");
   ```

4. **Error Information**: Include context in errors
   ```java
   ErrorInfo error = ErrorInfo.builder()
       .errorCode("VALIDATION_FAILED")
       .errorMessage("Order validation failed")
       .context(Map.of(
           "orderId", orderId,
           "reason", "missing items",
           "timestamp", Instant.now()
       ))
       .build();
   ```

5. **Type Safety**: Leverage Java generics
   ```java
   // Type-safe builder
   NodeExecutionTask.builder()
       .context(typedContext())  // Map<String, Object>
       .build();
   ```

## Common Utilities

### String Utilities

```java
// String formatting
String message = StringUtils.format(
    "Workflow {} failed: {}",
    runId,
    errorMessage
);

// String validation
if (StringUtils.isBlank(value)) {
    throw new IllegalArgumentException("Value required");
}
```

### Collection Utilities

```java
// Safe collection operations
List<String> items = CollectionUtils.nullSafe(
    data.get("items")
);

// Filtering
List<NodeExecution> failures = executions.stream()
    .filter(e -> e.isFailed())
    .collect(Collectors.toList());
```

### Time Utilities

```java
// Duration calculations
Duration duration = Duration.between(startTime, endTime);
long durationMs = duration.toMillis();

// Timestamp formatting
String timestamp = Instant.now().toString();
```

## Troubleshooting

### Null Pointer Issues

**Problem**: NullPointerException accessing data
- Check data exists in map
- Use Optional or defaults
- Validate data types

**Solution**:
```java
// Safe access
String value = (String) data.get("key");
if (value == null) {
    return NodeExecutionResult.failure(task, "Missing required field: key");
}
```

### Type Casting Errors

**Problem**: ClassCastException converting types
- Verify data type before casting
- Use Optional for unsafe casts
- Add type validation

**Solution**:
```java
// Validate before cast
Object value = data.get("amount");
if (value instanceof Double) {
    Double amount = (Double) value;
} else {
    throw new IllegalArgumentException("amount must be Double");
}
```

### Immutability Violations

**Problem**: Unexpected mutations of shared objects
- Use immutable collections
- Make defensive copies
- Document mutability contracts

**Solution**:
```java
// Use unmodifiable map
Map<String, Object> immutable = Collections.unmodifiableMap(data);

// Or use immutable collections from Vavr
io.vavr.collection.Map<String, Object> immutable = 
    io.vavr.collection.HashMap.ofAll(data);
```

## Integration Points

This module integrates with:

- **gamelan-engine-spi**: Uses domain models
- **gamelan-engine-core**: Uses runtime abstractions
- **gamelan-executor-runtime-core**: Uses execution entities
- **gamelan-protocol-grpc**: Uses serialization utilities
- **gamelan-protocol-kafka**: Uses serialization utilities

## See Also

- **[gamelan-engine-spi](../gamelan-engine-spi/README.md)**: Domain model definitions
- **[gamelan-engine-core](../gamelan-engine-core/README.md)**: Core engine implementation
- **[gamelan-executor-runtime-core](../gamelan-executor-runtime-core/README.md)**: Executor runtime abstractions
