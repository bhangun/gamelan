# Gamelan Workflow Engine 🥋

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![Quarkus](https://img.shields.io/badge/Quarkus-3.15.1-blue)](https://quarkus.io/)
[![Java](https://img.shields.io/badge/Java-21-orange)](https://openjdk.org/)

> **Gamelan** - A production-ready, enterprise-grade workflow orchestration engine for agentic AI, enterprise integration patterns, and business automation.

Named after the martial art known for its fluid, adaptive movements, Gamelan embodies flexibility, resilience, and precision in workflow orchestration.

## 🌟 Key Features

### Core Capabilities
- **Event Sourcing**: Complete audit trail with event replay capability
- **CQRS Pattern**: Optimized command and query paths
- **Multi-Protocol Dispatch**: Native support for **gRPC**, **Kafka**, and **REST** executors
- **Reactive Architecture**: Built on Quarkus and Mutiny for high-performance, non-blocking orchestration
- **Advanced Scheduling**: Redis-based task queuing with support for delayed retries and priority
- **State Machine**: Robust workflow state management with strictly validated transitions
- **Saga Pattern**: Automated compensation logic for distributed transactions
- **Multi-Tenancy**: Built-in support for tenant isolation at the database level

### Enterprise Features
- **Fault Tolerance**: Integrated circuit breakers and exponential backoff retry policies
- **Distributed Locking**: Redis-based coordination for concurrent workflow execution
- **Security**: Time-limited execution tokens to ensure result authenticity
- **Observability**: OpenTelemetry tracing, Prometheus metrics, and structured logging
- **Task Dead-lettering**: Automatic handling of tasks that exhaust retry attempts

## 🏗️ Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                    Client Applications                       │
│                  (REST, gRPC, or SDK)                       │
└──────────────────────┬──────────────────────────────────────┘
                       │
                       ▼
┌─────────────────────────────────────────────────────────────┐
│                   API Layer (Gamelan API)                      │
│          ┌──────────────┬────────────────┐                  │
│          │  REST API    │   gRPC API     │                  │
│          └──────────────┴────────────────┘                  │
└──────────────────────┬──────────────────────────────────────┘
                       │
                       ▼
┌─────────────────────────────────────────────────────────────┐
│              Core Workflow Engine (Gamelan Core)               │
│  ┌────────────────┬──────────────────┬─────────────────┐   │
│  │ WorkflowRun    │  RunManager      │  Execution      │   │
│  │  Aggregate     │  (Orchestrator)  │   Engine        │   │
│  │                │                  │ (Mutiny Based)  │   │
│  └────────────────┴──────────────────┴─────────────────┘   │
│  ┌────────────────┬──────────────────┬─────────────────┐   │
│  │ Event Store    │  Scheduler       │  Distributed    │   │
│  │ (Postgres)     │  (Redis Driven)  │  Locking (Redis)│   │
│  └────────────────┴──────────────────┴─────────────────┘   │
└──────────────────────┬──────────────────────────────────────┘
                       │
                       ▼
┌─────────────────────────────────────────────────────────────┐
│              Dispatcher Layer (Multi-Protocol)               │
│  ┌────────────┬────────────┬────────────┬────────────┐     │
│  │   gRPC     │   Kafka    │    REST    │    Custom  │     │
│  └──────┬─────┴──────┬─────┴──────┬─────┴──────┬─────┘     │
└─────────┼────────────┼────────────┼────────────┼───────────┘
          ▼            ▼            ▼            ▼
┌─────────────────────────────────────────────────────────────┐
│                  External Executors                          │
└─────────────────────────────────────────────────────────────┘
```

## 🚀 Quick Start

### Prerequisites
- Java 21+
- Maven 3.8+
- Docker (for PostgreSQL, Redis, and Kafka)

### Installation

1. **Clone and Build**
```bash
git clone https://github.com/kayys/gamelan-workflow-engine.git
cd gamelan-workflow-engine
mvn clean install
```

2. **Start Infrastructure**
```bash
# Recommended: Use the provided docker-compose
docker-compose up -d postgres redis kafka
```

3. **Run the Engine**
```bash
mvn quarkus:dev
```

## 📖 Usage Examples

### 1. Define a Workflow
Workflows are defined as a Directed Acyclic Graph (DAG) of nodes.

```java
WorkflowDefinition workflow = WorkflowDefinition.builder()
    .id(WorkflowDefinitionId.of("order-fulfillment"))
    .name("Order Fulfillment Process")
    .version("1.0.0")
    .addNode(NodeDefinition.builder()
        .id(NodeId.of("verify-inventory"))
        .type(NodeType.TASK)
        .executorType("inventory-service")
        .retryPolicy(new ExponentialRetryPolicy(3, Duration.ofSeconds(1)))
        .build())
    .addNode(NodeDefinition.builder()
        .id(NodeId.of("charge-card"))
        .type(NodeType.TASK)
        .executorType("payment-gateway")
        .dependsOn(List.of(NodeId.of("verify-inventory")))
        .critical(true)
        .build())
    .compensationPolicy(CompensationPolicy.DEFAULT)
    .build();
```

### 2. Implement an Executor (gRPC)
Executors receive tasks and report results using a secure execution token.

```java
@ApplicationScoped
public class InventoryExecutor implements WorkflowExecutor {
    
    @Override
    public Uni<NodeExecutionResult> execute(NodeExecutionTask task) {
        String sku = (String) task.context().get("sku");
        
        return checkStock(sku)
            .map(available -> available ? 
                NodeExecutionResult.success(task.runId(), task.nodeId(), task.attempt(), Map.of("status", "IN_STOCK"), task.token()) :
                NodeExecutionResult.failure(task.runId(), task.nodeId(), task.attempt(), new ErrorInfo("OUT_OF_STOCK", "Item is unavailable", null, null), task.token())
            );
    }

    @Override
    public String executorType() {
        return "inventory-service";
    }
}
```

### 3. Start a Workflow Run

```java
CreateRunRequest request = CreateRunRequest.builder()
    .workflowId("order-fulfillment")
    .inputs(Map.of("sku", "IPHONE-15", "amount", 999.00))
    .build();

runManager.createRun(request, tenantId)
    .flatMap(run -> runManager.startRun(run.getId(), tenantId))
    .subscribe().with(run -> System.out.println("Workflow running: " + run.getId()));
```

## 🔧 Configuration

### Application Properties

Key configuration options:

```properties
# Engine Configuration
gamelan.engine.max-concurrent-executions=1000
gamelan.engine.default-workflow-timeout=PT1H
gamelan.engine.event-sourcing.enabled=true

# Multi-tenancy
gamelan.tenancy.isolation-level=DISCRIMINATOR
gamelan.tenancy.resolution-strategy=HEADER

# Communication Strategy
gamelan.executor.communication-strategy=AUTO

# Service Registry
gamelan.registry.type=consul
gamelan.registry.consul.host=localhost
```

See `application.yml` for complete configuration options.

## 🔐 Security

### Multi-Tenancy

Gamelan provides three isolation levels:

1. **DISCRIMINATOR**: Shared database with tenant_id column (default)
2. **SCHEMA**: Separate schema per tenant
3. **DATABASE**: Separate database per tenant

### Authentication

Supports:
- JWT (recommended)
- OIDC
- API Keys (for executors)

### Execution Tokens

Every node execution requires a valid execution token:
- Generated by RunManager
- Time-limited (configurable)
- Cryptographically secure
- Validated on result submission

## 📊 Monitoring & Observability

### Metrics

Exposes Prometheus metrics:
- Workflow execution rate
- Success/failure rates
- Execution duration (p50, p95, p99)
- Active workflows count
- Task queue depth

### Distributed Tracing

OpenTelemetry integration:
- Trace workflow execution across services
- Correlate with external systems
- Performance profiling

### Health Checks

- **Liveness**: `/health/live`
- **Readiness**: `/health/ready`
- **Startup**: `/health/started`

## 🧪 Testing

```bash
# Unit tests
mvn test

# Integration tests
mvn verify

# Load tests (requires JMeter)
mvn jmeter:jmeter
```

## 📦 Deployment

### Kubernetes

```bash
# Build container
mvn clean package -Dquarkus.container-image.build=true

# Deploy to Kubernetes
kubectl apply -f k8s/
```

### Native Image

```bash
# Build native executable
mvn package -Pnative

# Run native executable
./target/gamelan-core-1.0.0-SNAPSHOT-runner
```

## 🛣️ Roadmap

- [ ] Visual workflow designer (Web UI)
- [ ] Temporal integration
- [ ] State machine visualization
- [ ] Advanced analytics dashboard
- [ ] AI-powered workflow optimization
- [ ] Multi-cloud support (AWS Step Functions, Azure Logic Apps compatibility)
- [ ] Workflow versioning and migration tools

## 🤝 Contributing

Contributions are welcome! Please read our [Contributing Guide](CONTRIBUTING.md) for details.

## 📄 License

Apache License 2.0 - see [LICENSE](LICENSE) file for details.

## 🙏 Acknowledgments

- Inspired by AWS Step Functions, Temporal, and Camunda
- Built with Quarkus, the Supersonic Subatomic Java Framework
- Event Sourcing patterns from Greg Young and Martin Fowler

## 📞 Support

- **Documentation**: [https://docs.gamelan.dev](https://docs.gamelan.dev)
- **Issues**: [GitHub Issues](https://github.com/kayys/gamelan/issues)
- **Discussions**: [GitHub Discussions](https://github.com/kayys/gamelan/discussions)
- **Email**: support@kayys.tech

---

**Built with ❤️ by the Kayys Team**