# Gamelan Protocol - gRPC

This module provides the **gRPC Protocol Buffer definitions and mappers** for the Gamelan Workflow Engine. It defines the high-performance, binary RPC communication contracts between the central workflow engine and distributed executors, enabling fast, synchronous task execution and status reporting.

## Overview

gRPC is a modern, high-performance RPC framework built on HTTP/2 that provides:
- **Low Latency**: Sub-millisecond response times ideal for interactive workflows
- **Binary Protocol**: Efficient serialization with Protocol Buffers (protobuf)
- **Bidirectional Streaming**: Support for real-time, long-lived connections
- **Load Balancing**: Built-in support for load balancers and service meshes
- **Strong Typing**: Type-safe communication with compile-time contract verification

## Key Features

- **Protocol Buffer Definitions**: Strongly-typed service contracts for workflow operations
- **Automatic Code Generation**: Java stubs and messages auto-generated from .proto files
- **Data Mapping**: `GrpcMapper` for converting between domain objects and protobuf messages
- **Executor Communication**: Services for task distribution and result collection
- **Workflow Management**: RPCs for creating, querying, and managing workflow runs
- **Streaming Support**: Server streaming for task distribution, client streaming for result reporting
- **Type Conversion**: Seamless conversion between Java domain objects and protobuf types

## Installation

Add the following dependency to your project:

```xml
<dependency>
    <groupId>tech.kayys.gamelan</groupId>
    <artifactId>gamelan-protocol-grpc</artifactId>
    <version>${gamelan.version}</version>
</dependency>
```

## Architecture

### Protocol Components

#### 1. Service Definitions (.proto files)

The core gRPC services defined in `gamelan.proto`:

**WorkflowService**: Manages workflow lifecycle and orchestration
```protobuf
service WorkflowService {
  rpc CreateRun(CreateRunRequest) returns (RunResponse);
  rpc StartRun(StartRunRequest) returns (RunResponse);
  rpc GetRun(GetRunRequest) returns (RunResponse);
  rpc CancelRun(CancelRunRequest) returns (google.protobuf.Empty);
  rpc StreamRunStatus(StreamRunStatusRequest) returns (stream RunStatusUpdate);
  // ... more operations
}
```

**ExecutorService**: Manages executor registration and task execution
```protobuf
service ExecutorService {
  rpc RegisterExecutor(RegisterExecutorRequest) returns (ExecutorRegistration);
  rpc StreamTasks(StreamTasksRequest) returns (stream ExecutionTask);
  rpc ReportResults(stream TaskResult) returns (google.protobuf.Empty);
  rpc ExecuteStream(stream ExecutorMessage) returns (stream EngineMessage);
  rpc Heartbeat(HeartbeatRequest) returns (google.protobuf.Empty);
  // ... more operations
}
```

#### 2. Message Types

Core message definitions covering:
- **Run Management**: CreateRunRequest, RunResponse, QueryRunsRequest
- **Task Execution**: ExecutionTask, TaskResult, StreamTasksRequest
- **Status Updates**: RunStatusUpdate, NodeExecutionUpdate
- **Executor Management**: RegisterExecutorRequest, HeartbeatRequest
- **Errors**: ExecutionError, ErrorInfo

#### 3. GrpcMapper

The `GrpcMapper` class provides bidirectional conversion between:
- Domain objects (WorkflowRun, NodeExecution, ExecutionContext)
- Protocol Buffer messages (RunResponse, ExecutionTask, TaskResult)

```java
@ApplicationScoped
public class GrpcMapper {
    // Workflow Run conversions
    public RunResponse toProtoRunResponse(WorkflowRun run) { ... }
    public WorkflowRun toDomainRun(RunResponse response) { ... }
    
    // Node Execution conversions
    public ExecutionTask toProtoTask(NodeExecutionTask task) { ... }
    public NodeExecutionTask toDomainTask(ExecutionTask proto) { ... }
    
    // Result conversions
    public TaskResult toProtoResult(NodeExecutionResult result) { ... }
    public NodeExecutionResult toDomainResult(TaskResult proto) { ... }
}
```

## Communication Flow

```
┌─────────────────────────────────────────────────────────┐
│ Gamelan Workflow Engine                                 │
│ - Orchestrates workflows                                │
│ - Creates/manages workflow runs                          │
│ - Distributes tasks to executors                         │
└─────────────────────────────────────────────────────────┘
                         ↓
              ┌──────────────────────┐
              │   gRPC Protocol      │
              │ (HTTP/2 + Protobuf)  │
              │                      │
              │ Synchronous RPC      │
              │ Binary serialization │
              │ Bidirectional stream │
              └──────────────────────┘
                         ↓
┌─────────────────────────────────────────────────────────┐
│ Executor Service (Remote)                               │
│ - Receives tasks via ExecutorService                    │
│ - Processes tasks                                       │
│ - Reports results via ReportResults RPC                 │
└─────────────────────────────────────────────────────────┘
```

## Service Definitions

### WorkflowService

Provides endpoints for workflow lifecycle management:

| RPC | Request | Response | Description |
|-----|---------|----------|-------------|
| **CreateRun** | CreateRunRequest | RunResponse | Create a new workflow execution |
| **StartRun** | StartRunRequest | RunResponse | Start a suspended workflow |
| **GetRun** | GetRunRequest | RunResponse | Retrieve workflow run details |
| **CancelRun** | CancelRunRequest | Empty | Cancel a running workflow |
| **SuspendRun** | SuspendRunRequest | RunResponse | Suspend a workflow temporarily |
| **ResumeRun** | ResumeRunRequest | RunResponse | Resume a suspended workflow |
| **SignalRun** | SignalRequest | Empty | Send signal to running workflow |
| **GetExecutionHistory** | GetExecutionHistoryRequest | ExecutionHistoryResponse | Get detailed execution history |
| **QueryRuns** | QueryRunsRequest | QueryRunsResponse | Query multiple workflow runs |
| **StreamRunStatus** | StreamRunStatusRequest | stream RunStatusUpdate | Stream status updates (server streaming) |

### ExecutorService

Provides endpoints for task execution and status reporting:

| RPC | Request | Response | Description |
|-----|---------|----------|-------------|
| **RegisterExecutor** | RegisterExecutorRequest | ExecutorRegistration | Register executor with engine |
| **UnregisterExecutor** | UnregisterExecutorRequest | Empty | Unregister from engine |
| **Heartbeat** | HeartbeatRequest | Empty | Send liveness signal |
| **StreamTasks** | StreamTasksRequest | stream ExecutionTask | Receive task assignments (server streaming) |
| **ReportResults** | stream TaskResult | Empty | Report task results (client streaming) |
| **ExecuteStream** | stream ExecutorMessage | stream EngineMessage | Bidirectional real-time communication |

## Message Types

### Core Execution Messages

**ExecutionTask**: Represents a task assigned to an executor
```protobuf
message ExecutionTask {
  string task_id = 1;
  string run_id = 2;
  string node_id = 3;
  int32 attempt = 4;
  string token = 5;
  google.protobuf.Struct context = 6;           // Input data
  map<string, string> metadata = 7;
  google.protobuf.Timestamp assigned_at = 8;
}
```

**TaskResult**: Result of task execution
```protobuf
message TaskResult {
  string task_id = 1;
  string run_id = 2;
  string node_id = 3;
  int32 attempt = 4;
  string token = 5;
  ExecutionStatus status = 6;                    // SUCCESS, FAILURE, TIMEOUT
  google.protobuf.Struct output = 7;             // Output data
  ErrorInfo error = 8;                           // Error details if failed
  google.protobuf.Duration execution_time = 9;
}
```

**RunResponse**: Workflow run state
```protobuf
message RunResponse {
  string run_id = 1;
  string workflow_definition_id = 2;
  RunStatus status = 3;                          // CREATED, RUNNING, COMPLETED, FAILED, CANCELLED
  google.protobuf.Struct variables = 4;          // Workflow variables
  map<string, NodeExecution> node_executions = 5;
  repeated string execution_path = 6;
  google.protobuf.Timestamp created_at = 7;
  google.protobuf.Timestamp started_at = 8;
  google.protobuf.Timestamp completed_at = 9;
}
```

## Usage Examples

### Using GrpcMapper for Conversions

```java
@Inject
GrpcMapper grpcMapper;

// Convert domain object to protobuf
public RunResponse sendWorkflowStatus(WorkflowRun run) {
    return grpcMapper.toProtoRunResponse(run);
}

// Convert protobuf to domain object
public WorkflowRun processRunResponse(RunResponse response) {
    return grpcMapper.toDomainRun(response);
}

// Convert task for transmission
public ExecutionTask assignTaskToExecutor(NodeExecutionTask task) {
    return grpcMapper.toProtoTask(task);
}
```

### Accessing Workflow Data in gRPC Messages

```java
// Extract data from protobuf messages
RunResponse response = ...; // from gRPC call

String runId = response.getRunId();
RunStatus status = response.getStatus();
Map<String, String> variables = response.getVariablesMap();

// Access node execution details
response.getNodeExecutionsMap().forEach((nodeId, nodeExec) -> {
    NodeExecutionStatus nodeStatus = nodeExec.getStatus();
    long duration = nodeExec.getDurationMs();
    Timestamp completedAt = nodeExec.getCompletedAt();
});
```

## Protocol Buffer Type Mappings

### Common Type Conversions

| Java Type | Protobuf Type | Notes |
|-----------|---------------|-------|
| `String` | `string` | Native string type |
| `Integer/Long` | `int32`/`int64` | Numeric types |
| `Instant` | `google.protobuf.Timestamp` | Time representation |
| `Duration` | `google.protobuf.Duration` | Time intervals |
| `Map<String, Object>` | `google.protobuf.Struct` | JSON-like structures |
| `List<String>` | `repeated string` | Collections |
| `Enum` | `enum` | Type-safe enumerations |

### Struct Conversion (JSON-like Data)

The `google.protobuf.Struct` type allows passing arbitrary JSON-like data:

```java
// Create protobuf Struct from Map
Map<String, Object> data = Map.of(
    "amount", 100.0,
    "currency", "USD",
    "items", List.of("item1", "item2")
);
google.protobuf.Struct struct = grpcMapper.mapToStruct(data);

// Extract data from Struct
Map<String, Object> extracted = grpcMapper.mapFromStruct(struct);
```

## Best Practices

1. **Type Safety**: Leverage protobuf's compile-time type checking to catch errors early.

2. **Efficient Serialization**: Use binary protobuf encoding for network efficiency compared to JSON.

3. **Backward Compatibility**: When modifying .proto files:
   - Add new fields with higher field numbers
   - Never reuse field numbers
   - Mark deprecated fields with `[deprecated=true]`

4. **Message Validation**: Always validate required fields are populated before sending:
   ```java
   ExecutionTask task = grpcMapper.toProtoTask(domainTask);
   if (!task.hasToken() || task.getRunId().isEmpty()) {
       throw new IllegalArgumentException("Missing required fields");
   }
   ```

5. **Error Handling**: Map domain errors to protobuf ErrorInfo:
   ```java
   ErrorInfo error = ErrorInfo.newBuilder()
       .setCode("INVALID_INPUT")
       .setMessage(exception.getMessage())
       .putContext("nodeId", nodeId)
       .build();
   ```

6. **Streaming Efficiency**: Use bidirectional streaming for long-running operations to avoid repeated connection overhead.

7. **Struct Serialization**: Be cautious with `google.protobuf.Struct` - it doesn't preserve type information; numbers might be deserialized as floats.

## Protobuf Generation

The module uses Maven's protobuf compiler plugin to auto-generate Java code:

```xml
<plugin>
    <groupId>io.quarkus</groupId>
    <artifactId>quarkus-maven-plugin</artifactId>
    <configuration>
        <goals>
            <goal>generate-code</goal>
        </goals>
    </configuration>
</plugin>
```

Generated code is produced in `target/generated-sources/protobuf/`.

### Regenerating Protocol Files

If you modify `.proto` files:

```bash
mvn clean compile
```

This will:
1. Parse .proto files
2. Generate Java stubs and message classes
3. Create builder patterns for message construction
4. Compile generated code

## Troubleshooting

### Protobuf Version Mismatch

**Problem**: Incompatible protocol versions between client and server
- Ensure both engine and executors use the same version of `gamelan-protocol-grpc`
- Check that protobuf runtime versions match

**Solution**:
```xml
<!-- Ensure consistent versioning -->
<gamelan.version>1.0.0</gamelan.version>
```

### Message Serialization Issues

**Problem**: Fields missing or incorrectly serialized
- Verify field numbers are unique in message definitions
- Check that custom types are properly mapped by GrpcMapper
- Ensure `google.protobuf.Struct` is used correctly for dynamic data

**Solution**:
```java
// Use mapper for consistent serialization
RunResponse response = grpcMapper.toProtoRunResponse(workflowRun);
// Don't manually construct protobuf messages
```

### Code Generation Failures

**Problem**: Generated code doesn't compile
- Check .proto file syntax
- Verify all imported types are available
- Review compiler error messages carefully

**Solution**:
```bash
mvn clean
mvn -X compile  # Verbose output for debugging
```

## Performance Considerations

1. **Binary Format**: gRPC's binary protocol is 5-10x more efficient than JSON
2. **Streaming**: Use streaming RPCs for high-volume message exchange
3. **Connection Reuse**: gRPC maintains persistent HTTP/2 connections
4. **Message Size**: Keep messages small; use references instead of embedding large objects
5. **Compression**: Enable gRPC compression for large messages:
   ```properties
   grpc.compression=gzip
   ```

## Integration with Remote Executors

This protocol module is used by `gamelan-sdk-executor-remote` for gRPC transport. Executors:

1. Import `gamelan-protocol-grpc`
2. Implement `ExecutorService` endpoints
3. Use `GrpcMapper` to convert between domain and proto types
4. Register with engine via `RegisterExecutor` RPC
5. Receive tasks via `StreamTasks`
6. Report results via `ReportResults`

## See Also

- **[Gamelan Protocol - Kafka](../gamelan-protocol-kafka/README.md)**: For asynchronous event-based communication
- **[Remote Executor SDK](../../sdk/gamelan-sdk-executor-remote/README.md)**: Uses this protocol for gRPC transport
- **[Gamelan Protocol Buffers](gamelan.proto)**: Full .proto file definitions
