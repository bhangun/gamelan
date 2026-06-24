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

Task context keeps node configuration keys at the top level for backward-compatible executors. Gamelan also adds reserved runtime keys so custom executors can read workflow data without depending on storage internals:

- `task.taskId()` / `task.idempotencyKey()`: stable `runId:nodeId:attempt` identity for dedupe and tracing.
- `NodeExecutionTask.WORKFLOW_VARIABLES_KEY`: workflow inputs plus prior node outputs.
- `NodeExecutionTask.NODE_CONFIGURATION_KEY`: original node configuration snapshot.
- `NodeExecutionTask.RUN_ID_KEY`, `NODE_ID_KEY`, `TENANT_ID_KEY`, `WORKFLOW_DEFINITION_ID_KEY`, `ATTEMPT_KEY`: dispatch metadata.
- `NodeExecutionTask.TIMEOUT_SECONDS_KEY`: positive node timeout propagated to remote/streaming executors.

If the node does not define a `context` configuration key, Gamelan aliases workflow variables into `NodeExecutionTask.LEGACY_CONTEXT_KEY` for script/agent executors.

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

# Orchestrator Backpressure
gamelan.orchestrator.no-executor-policy=fail
gamelan.orchestrator.max-ready-nodes-per-cycle=256
gamelan.orchestrator.max-concurrent-dispatches=64
gamelan.workflow.compilation.cache.enabled=true
gamelan.workflow.compilation.cache.max-size=1024
gamelan.workflow.planning.validate-definition=true

# Recovery Sweeper
gamelan.recovery.enabled=true
gamelan.recovery.scan-interval=30s
gamelan.recovery.page-size=100
gamelan.recovery.max-concurrent-runs=4
gamelan.recovery.max-scan-runs=10000
gamelan.recovery.timeout-grace=30s
gamelan.recovery.replay-consistency-mode=best-effort
gamelan.recovery.distributed-lease.enabled=false
gamelan.recovery.distributed-lease.name=workflow-recovery
gamelan.recovery.distributed-lease.owner-id=
gamelan.recovery.distributed-lease.ttl=2m
gamelan.recovery.distributed-lease.renew-interval=0s

# Retry Wake-up Queue
gamelan.retry.scan-interval=5s
gamelan.retry.redis.batch-size=50
gamelan.retry.redis.claim-ttl=30s

# Multi-tenancy
gamelan.tenancy.isolation-level=DISCRIMINATOR
gamelan.tenancy.resolution-strategy=HEADER

# Communication Strategy
gamelan.executor.communication-strategy=AUTO

# Service Registry
gamelan.registry.type=consul
gamelan.registry.consul.host=localhost
```

Retry wake-up entries include tenant context and retry attempt when scheduled through `scheduleRetry(runId, tenantId, nodeId, attempt, delay)` and still decode legacy run/node entries already present in local or Redis queues. Redis scheduling preserves the earliest score atomically per encoded retry attempt and skips entries already claimed in the processing set, so duplicate scheduling cannot push a retry later or leave a duplicate after an in-flight publish succeeds. This keeps multi-tenant retries routed to the same tenant-aware run wake-up path used by lifecycle, recovery, and orchestrator events.

The orchestrator routes executor assignment through an admission controller before
dispatch. Admission decisions are reported as `dispatch`, `wait_for_executor`,
`reject`, `dead_letter`, or `defer_capacity` and counted with
`gamelan.orchestrator.admission.decisions{action,reason,policy}`. The default
`gamelan.orchestrator.no-executor-policy=fail` reserves and fails a node when no
compatible executor exists; `wait` leaves it pending so local/offline or elastic
agent profiles can accept work after executors appear.

Workflow definitions are compiled into a bounded topology cache before runtime
planning. The compiled view precomputes node indexes, dependency/dependent
indexes, start nodes, and DAG topological order, reducing repeated graph work
during high-volume workflow, business automation, and agent orchestration runs.
Tune it with `gamelan.workflow.compilation.cache.enabled` and
`gamelan.workflow.compilation.cache.max-size`. Cache behavior is observable via
`gamelan.workflow.compilation.cache.requests{result="hit|miss|bypass"}`,
`gamelan.workflow.compilation.cache.evictions`,
`gamelan.workflow.compilation.cache.invalidations`,
`gamelan.workflow.compilation.cache.size`, and
`gamelan.workflow.compilation.duration{source="cache_miss|bypass"}`. Definition
updates and deletes explicitly invalidate compiled entries for the affected
tenant/definition instead of waiting for LRU pressure.

Execution planning emits low-cardinality workflow-engine metrics:
`gamelan.workflow.planning.plans{outcome}`,
`gamelan.workflow.planning.duration{outcome}`, and
`gamelan.workflow.planning.ready_nodes{outcome}`. Outcomes are bounded to
`ready`, `waiting`, `complete`, `stuck`, `inactive`, and `failure`, so dashboards
can track planner health without tenant/run cardinality. Runtime planning
validates workflow definitions structurally before compiling/executing them by
default. Invalid definitions raise a controlled planning failure and orchestrator
drive marks the run failed with `WORKFLOW_DEFINITION_INVALID` instead of leaving
it silently RUNNING. Trusted-definition/high-throughput profiles can disable this
guard with `gamelan.workflow.planning.validate-definition=false`.

Workflow validation keeps structural errors as structured `ValidationResult.errors`
and isolates custom DAG validator plugins. Plugin lookup failures, null responses,
or plugin exceptions are converted into validation errors instead of crashing
definition validation. Validation health is observable with
`gamelan.workflow.validation.requests{outcome="valid|invalid|null_definition"}`,
`gamelan.workflow.validation.duration{outcome}`, and
`gamelan.workflow.validation.errors{source}` where sources are bounded to
`null_definition`, `structural`, `plugin_rule`, `plugin_runtime`,
`plugin_lookup`, and `plugin_service`. Definition create/update uses a dedicated
admission gate before persistence: definitions are validated, custom DAG
validator plugins are isolated, and accepted active definitions are compiled
before they enter the registry. Deactivation still bypasses admission so invalid
legacy definitions can be safely turned off. Admission health is observable with
`gamelan.workflow.definition.admissions{outcome}` and
`gamelan.workflow.definition.admission.duration{outcome}`. Outcomes are bounded to
`accepted`, `rejected`, `validation_failure`, and `compilation_failure`.
Terminal run failures are observable with
`gamelan.workflow.run.failures{reason}`. Reasons are bounded to
`workflow_definition_invalid`, `workflow_planning_failed`, `workflow_stuck`,
`critical_node_failed`, `compensation_failed`, `dispatch_failed`,
`no_executor_available`, `workflow_failed`, `unknown`, and `other`; the counter
increments only when a run durably transitions into terminal `FAILED`, not when a
failed workflow enters compensation.
Compensation finalization is idempotent for matching terminal states: duplicate
completion on an already `COMPENSATED` run and duplicate failure on a
compensation-failed `FAILED` run wake the orchestrator without mutating run state
or appending duplicate audit history.
Startup is also recovery-safe for persisted `PENDING` runs: `startRun` only
short-circuits already `RUNNING` runs, while `PENDING` runs can resume startup
under the repository lock, schedule missing start nodes, persist the resulting
state, and publish the normal `run-started` wake-up. Invalid legacy definitions
are rejected before startup mutates run state, so they cannot create parked active
runs or false `RUNNING` audit records. New runs are rejected before persistence
when the resolved workflow definition is invalid.
Non-auto-start run creation does not require a Vert.x event bus; embedded,
offline, and file-backed agent profiles can create durable runs even when
transport notifications are intentionally absent.

Recovery replay consistency can be profiled with `gamelan.recovery.replay-consistency-mode`:
`disabled` skips event replay checks, `best-effort` checks when replay dependencies are available,
and `strict` refuses recovery mutation when the event store or workflow definition repository is unavailable.
Aggregate replay and execution-history reconstruction reject event streams whose
envelopes do not match the requested run: events must carry the same run id, and
the aggregate creation event must match tenant, workflow definition id, and any
concrete workflow version. History reconstruction also rejects conflicting
tenant or workflow identity metadata inside the same run stream. This prevents
cross-run or cross-tenant event-store corruption from being rehydrated as a
valid aggregate or audit history.
Strict-mode dependency failures are reported separately from replay drift as `replayUnavailable`
in recovery sweep results, `gamelan.recovery.replay.unavailable`, and
`gamelan.recovery.replay.blocks{status="unavailable"}` metrics.
Per-run recovery results also expose a compact replay block summary with status, mismatch count,
and capped mismatch field names for operator views without payload values. Sweep results aggregate
those field names by replay status so operators can see which fields most often block recovery.
Large deployments can bound repository scan candidates per sweep with
`gamelan.recovery.max-scan-runs`; when reached, the sweep returns
`scanLimitReached=true` and increments `gamelan.recovery.scan.limit_reached`.
Built-in memory, file, and Postgres repositories use cursor-based active-run
scans for recovery; custom repositories can implement `scanActiveRunsForRecovery`
to avoid offset paging under concurrent workflow mutations.
Recovery also emits scan health counters for pages, raw candidates, duplicate
candidates, invalid candidates, and stalled cursors under `gamelan.recovery.scan.*`.
Recovery sweeps are guarded by an in-process single-flight gate so a manual/API-triggered
sweep cannot duplicate scan and lock work while another sweep is still running in the same
engine instance. Overlap skips return `skippedSweeps=1` with reason `already_running` and
increment `gamelan.recovery.sweeps.skipped{reason="already_running"}`. Skipped sweep
latency is recorded separately as `gamelan.recovery.sweep.skipped.duration{reason}`.
Postgres and file-backed runtimes can enable `gamelan.recovery.distributed-lease.enabled`
to coordinate sweeps across engine instances or local agent processes. Postgres stores
leases in `workflow_recovery_leases` and evaluates expiry with the database clock;
file persistence stores leases under
`<gamelan.workflow.persistence.file.root>/recovery-leases` with process-safe file locks
and quarantines unreadable lease records under `recovery-leases/corrupt`.
Lease contention returns reason `lease_held` and increments
`gamelan.recovery.sweeps.skipped{reason="lease_held"}`. If leasing is enabled
but the active persistence profile does not provide a `WorkflowRecoveryLeaseRepository`,
the sweep is skipped with reason `lease_repository_unavailable` instead of running
uncoordinated. Long sweeps renew the lease at progress checkpoints; set
`gamelan.recovery.distributed-lease.renew-interval` to a positive duration below the
TTL or leave it at `0s` to renew halfway through the configured TTL. Lease acquire,
renew, and release health is reported through
`gamelan.recovery.lease.operations{operation,outcome}`.
Set `gamelan.recovery.distributed-lease.owner-id` when a deployment has a stable
process identity; otherwise Gamelan derives one from `gamelan.engine.id` and finally
falls back to a per-process random owner.
Locked recovery outcomes distinguish healthy no-op runs (`gamelan.recovery.runs.no_work`),
active non-running runs deferred to other lifecycle handlers
(`gamelan.recovery.runs.deferred_active`), and candidates skipped because state changed
before lock processing (`gamelan.recovery.runs.skipped_after_lock`).
The bounded tagged counter `gamelan.recovery.runs.locked_status{outcome,status}`
breaks those outcomes down by `RunStatus` without tenant/run cardinality.

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
