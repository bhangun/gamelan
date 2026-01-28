# Workflow-Gamelan Skills

Executable capability packages for workflow-gamelan, built on the [agent skills standard](https://agentskills.io).

Skills capture step-by-step instructions for AI agents to accomplish workflow orchestration tasks using workflow-gamelan.

## Quick Start Skills

Each skill folder contains:
- `SKILL.md` - Instructions and metadata
- `scripts/` - Executable code examples
- `references/` - Supporting documentation

### Available Skills

#### 1. [Define Workflow](./define-workflow/)
Create workflow definitions (DAG/Saga) with nodes, executors, and compensation logic.

**When to use**: Building new workflows or updating existing ones
**Key features**: DAG/Saga pattern, compensation handlers, retry policies, input/output mapping

#### 2. [Register Executor](./register-executor/)
Register and configure executors (local, gRPC, Kafka) that perform workflow node tasks.

**When to use**: Setting up task executors
**Key features**: Multi-protocol support, health checks, service discovery, circuit breakers

#### 3. [Execute Workflow](./execute-workflow/)
Trigger workflow execution instances with monitoring, result handling, and lifecycle management.

**When to use**: Running workflows and tracking progress
**Key features**: Sync/async execution, progress tracking, cancellation, result handling

#### 4. [Handle Saga Compensation](./handle-saga-compensation/)
Implement Saga pattern compensation for distributed transaction rollback and failure recovery.

**When to use**: Complex multi-step workflows requiring rollback capability
**Key features**: Sequential/parallel compensation, custom strategies, idempotency

#### 5. [Build Plugin](./build-plugin/)
Build custom workflow-gamelan plugins to extend orchestration logic and execution behavior.

**When to use**: Custom validation, interceptors, or event listeners
**Key features**: Execution interceptors, event listeners, custom validators, plugin lifecycle

#### 6. [Monitor Workflow](./monitor-workflow/)
Monitor workflow execution with distributed tracing, metrics, logging, and alerting.

**When to use**: Understanding workflow behavior and debugging
**Key features**: OpenTelemetry tracing, Prometheus metrics, structured logging, Kafka events

## Skill Structure

```
workflow-gamelan/skills/
├── define-workflow/
│   ├── SKILL.md                 # Instructions
│   ├── scripts/                 # Code examples
│   └── references/              # Additional docs
├── register-executor/
│   ├── SKILL.md
│   ├── scripts/
│   └── references/
├── execute-workflow/
│   ├── SKILL.md
│   ├── scripts/
│   └── references/
├── handle-saga-compensation/
│   ├── SKILL.md
│   ├── scripts/
│   └── references/
├── build-plugin/
│   ├── SKILL.md
│   ├── scripts/
│   └── references/
├── monitor-workflow/
│   ├── SKILL.md
│   ├── scripts/
│   └── references/
└── README.md (this file)
```

## Using Skills with AI Agents

Skills are designed for AI agents (like Claude, GPT, etc.) to understand and follow.

### Explicit Skill Invocation

Ask the agent to use a specific skill:

```
"Use the $define-workflow skill to create an order processing workflow"
```

### Implicit Skill Invocation

Describe what you need; agent picks appropriate skill:

```
"Create a workflow that validates orders, charges payment, then ships items with automatic rollback if anything fails"
```

The agent will automatically select `$define-workflow` + `$handle-saga-compensation` skills.

## Skills Progression Path

```
┌─────────────────────────────────┐
│ Start Here: Define Workflow     │  Workflow structure
├─────────────────────────────────┤
│ Register Executor               │  Set up task executors
├─────────────────────────────────┤
│ Execute Workflow                │  Run workflows
├─────────────────────────────────┤
│ Monitor Workflow                │  Track & debug
├─────────────────────────────────┤
│ Handle Saga Compensation        │  Failure recovery
├─────────────────────────────────┤
│ Build Plugin                    │  Advanced customization
└─────────────────────────────────┘
```

## Common Workflows

### Simple Sequential Workflow
1. Use `define-workflow` to define 3 steps in sequence
2. Use `register-executor` for each step
3. Use `execute-workflow` to run it
4. Use `monitor-workflow` to track progress

### Distributed Transaction (Saga)
1. Use `define-workflow` with compensation handlers
2. Use `handle-saga-compensation` to test rollback
3. Use `register-executor` for each service
4. Use `execute-workflow` with compensation enabled
5. Use `monitor-workflow` to verify compensation

### Microservices Orchestration
1. Use `define-workflow` with parallel branches
2. Use `register-executor` with gRPC endpoints
3. Use `execute-workflow` for each request
4. Use `monitor-workflow` with distributed tracing
5. Use `build-plugin` for custom routing/validation

## Core Concepts

### Workflow Definition
Describes the structure: nodes, edges, inputs, outputs, compensation.

### Executor
Service or function that performs work for a workflow node. Can be:
- Local (in-process)
- gRPC (high-performance RPC)
- Kafka (async event-driven)

### Workflow Execution
Instance of a workflow with specific inputs and state.

### Saga Pattern
Distributed transaction pattern with compensation for failures.

### Compensation
Rollback logic that executes when a workflow fails (refund, cancel, revert, etc.).

### Plugin
Extends platform behavior without modifying core code.

## Example: Order Processing System

```java
// 1. Define workflow (define-workflow skill)
WorkflowDefinition workflow = WorkflowDefinition.builder()
  .id("order-processing")
  .addNode("validate-order", "order-validator")
  .addNode("charge-payment", "payment-service")
  .addNode("ship-order", "shipping-service")
  .addEdge("validate-order", "charge-payment")
  .addEdge("charge-payment", "ship-order")
  .addCompensation("charge-payment", "refund")
  .addCompensation("ship-order", "cancel-shipment")
  .build();

// 2. Register executors (register-executor skill)
registry.register("order-validator", 
  new OrderValidatorExecutor());
registry.register("payment-service", 
  new PaymentGrpcExecutor("payment:50051"));
registry.register("shipping-service",
  new ShippingKafkaExecutor("shipping-tasks"));

// 3. Execute workflow (execute-workflow skill)
WorkflowExecutionRequest request = 
  WorkflowExecutionRequest.builder()
    .workflowId("order-processing")
    .inputs(Map.of(
      "orderId", "ORD-123",
      "amount", 99.99
    ))
    .enableCompensation(true)
    .build();

Uni<WorkflowExecutionResult> result = 
  executionService.execute(request);

// 4. Monitor execution (monitor-workflow skill)
executionService.watchExecution(executionId)
  .onItem().invoke(event -> {
    meterRegistry.counter("workflow.events",
      "type", event.getType().toString()
    ).increment();
  });

// 5. Handle failures with compensation 
// (handle-saga-compensation skill)
result.onFailure().invoke(error -> {
  // Compensation automatically triggered
  // Refund payment, cancel shipment
});
```

## Workflow Patterns

### Sequential Pattern
```
Step1 → Step2 → Step3
```

### Parallel Pattern
```
      ├→ Step2
Step1─┤
      └→ Step3
        ↓
      Step4
```

### Conditional Pattern
```
Step1 → [if condition] → {Step2 or Step3} → Step4
```

### Saga Pattern
```
Step1 ✓
Step2 ✓
Step3 ✗  → Rollback: Step3 ✓ → Step2 ✓ → Step1 ✓
```

## Best Practices

1. **Name clearly** - Workflow and node names should be descriptive
2. **Plan compensation** - Always think about failure scenarios
3. **Set timeouts** - Prevent workflows from hanging
4. **Monitor execution** - Always enable observability
5. **Test failures** - Simulate failure cases
6. **Use retries** - Handle transient failures
7. **Document flows** - Add descriptions and comments

## Architecture

### Layered Architecture

```
┌──────────────────────────────────┐
│ API Layer (REST, gRPC)           │
├──────────────────────────────────┤
│ Workflow Engine                  │
│ - Definition Registry            │
│ - Execution Engine               │
│ - State Management               │
├──────────────────────────────────┤
│ Executor Layer                   │
│ - Local, gRPC, Kafka             │
│ - Service Discovery              │
│ - Health Checks                  │
├──────────────────────────────────┤
│ Persistence Layer                │
│ - Event Sourcing                 │
│ - State Snapshots                │
├──────────────────────────────────┤
│ Plugin System                    │
│ - Interceptors                   │
│ - Event Listeners                │
└──────────────────────────────────┘
```

### High-Level Flow

```
Define Workflow
    ↓
Register Executors
    ↓
Execute Workflow
    ↓
Monitor Progress
    ↓
Handle Results/Failures
    ↓
Compensation (if needed)
```

## Key Features

- **DAG & Saga Workflows** - Sequential, parallel, conditional
- **Multi-Protocol Executors** - Local, gRPC, Kafka
- **Distributed Transactions** - Saga pattern with compensation
- **Event Sourcing** - Complete execution history
- **Service Discovery** - Consul, Kubernetes integration
- **Observability** - OpenTelemetry, Prometheus, Kafka
- **Plugin System** - Extend without modifying core
- **Production Ready** - Distributed locking, clustering

## Troubleshooting

If a skill isn't working:

1. Check the SKILL.md for prerequisites
2. Review error handling section
3. Check workflow-gamelan logs
4. Use `monitor-workflow` skill to debug
5. Verify executors are registered and healthy

## Contributing

To add new skills:

1. Create new skill directory with SKILL.md
2. Add scripts/ and references/
3. Test with AI agents
4. Submit as contribution

## Links

- [Workflow-Gamelan Documentation](../README.md)
- [Agent Skills Standard](https://agentskills.io)
- [Gamelan Plugin System](../docs/PLUGIN_SYSTEM.md)
- [Saga Pattern](../docs/SAGA_PATTERN.md)
