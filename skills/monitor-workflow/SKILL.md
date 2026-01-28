---
name: monitor-workflow
description: Monitor workflow execution with distributed tracing, metrics, logging, and alerting
metadata:
  short-description: Observe workflow behavior
  category: observability
  difficulty: beginner
---

# Monitor Workflow Skill

Monitor and observe workflow execution with distributed tracing, Prometheus metrics, structured logging, and real-time alerting.

## When to Use

- You need to track workflow performance
- You want to monitor error rates and failures
- You need distributed tracing across services
- You want to debug workflow issues
- You need SLA monitoring and alerting

## Monitoring Stack

```
┌──────────────────────────────────────────┐
│  OpenTelemetry (Tracing & Metrics)       │
│                                          │
│ ├─ Jaeger/Tempo (trace storage)          │
│ ├─ Prometheus (metrics storage)          │
│ ├─ Loki (logs aggregation)               │
│ └─ Grafana (visualization)               │
│                                          │
│ Kafka (Event Streaming)                  │
│ Structured Logging (JSON)                │
└──────────────────────────────────────────┘
```

## Steps

### 1. Enable OpenTelemetry Tracing

```yaml
# application.properties
quarkus.otel.enabled=true
quarkus.otel.exporter.otlp.endpoint=http://jaeger:4317
quarkus.otel.traces.exporter=otlp
quarkus.otel.logs.exporter=otlp
quarkus.otel.metric.export.interval=5000
```

### 2. Enable Metrics Export

```yaml
quarkus.micrometer.enabled=true
quarkus.micrometer.export.prometheus.enabled=true

# Custom metrics
gamelan.metrics.enabled=true
gamelan.metrics.export.interval=10s
```

### 3. Trace Workflow Execution

```java
@Traced  // Automatic tracing
public Uni<WorkflowExecutionResult> executeWorkflow(
    WorkflowExecutionRequest request) {
  
  // Parent span created automatically
  Span span = Tracer.getCurrentSpan();
  span.setAttribute("workflow_id", 
    request.getWorkflowId());
  span.setAttribute("execution_id", 
    request.getExecutionId());
  span.setAttribute("correlation_id", 
    request.getCorrelationId());
  
  return executionService.execute(request)
    .onItem().invoke(result -> {
      span.addEvent("workflow_completed", Attributes.of(
        AttributeKey.stringKey("status"), 
          result.getStatus().toString(),
        AttributeKey.longKey("duration_ms"), 
          result.getDuration().toMillis()
      ));
    });
}
```

### 4. Trace Node Execution

```java
executionService.watchExecution(executionId)
  .onItem().invoke(event -> {
    if (event.getType() == 
        WorkflowEventType.NODE_STARTED) {
      
      // Child span for each node
      Span nodeSpan = Tracer.startSpan("node.execute");
      nodeSpan.setAttribute("node_id", event.getNodeId());
      nodeSpan.setAttribute("executor_id", 
        event.getExecutorId());
      
    } else if (event.getType() == 
        WorkflowEventType.NODE_COMPLETED) {
      
      // End span
      Tracer.endSpan();
    }
  });
```

### 5. Track Custom Metrics

```java
@Inject
MeterRegistry meterRegistry;

// Counters
Counter executionCounter = meterRegistry.counter(
  "workflow.executions",
  "workflow", "order-processing",
  "status", "success"
);
executionCounter.increment();

// Timers
Timer.Sample sample = Timer.start(meterRegistry);
try {
  result = executionService.execute(request);
} finally {
  sample.stop(Timer.builder("workflow.duration")
    .tag("workflow", request.getWorkflowId())
    .publishPercentiles(0.5, 0.95, 0.99)
    .register(meterRegistry)
  );
}

// Node latencies
meterRegistry.timer("node.duration",
  "workflow", "order-processing",
  "node", "charge-payment"
).record(nodeLatency, TimeUnit.MILLISECONDS);

// Gauges (active workflows)
meterRegistry.gauge("workflow.active",
  activeWorkflows,
  AtomicInteger::get
);

// Compensation metrics
meterRegistry.counter("workflow.compensations",
  "status", "success"
).increment();
```

### 6. Structured Logging

```java
import org.jboss.logging.Logger;

Logger log = Logger.getLogger(MyService.class);

// Log workflow execution
log.infof(
  "Workflow execution started: " +
  "workflowId=%s, executionId=%s, correlationId=%s",
  request.getWorkflowId(),
  request.getExecutionId(),
  request.getCorrelationId()
);

// Log node execution with context
log.infof(
  "Node execution: workflowId=%s, nodeId=%s, " +
  "executorId=%s, status=%s, duration=%dms",
  execution.getWorkflowId(),
  nodeEvent.getNodeId(),
  nodeEvent.getExecutorId(),
  nodeEvent.getStatus(),
  nodeEvent.getDuration()
);

// Error logging
try {
  result = executionService.execute(request);
} catch (Exception e) {
  log.errorf(e,
    "Workflow failed: workflowId=%s, " +
    "executionId=%s, error=%s",
    request.getWorkflowId(),
    request.getExecutionId(),
    e.getMessage()
  );
}
```

### 7. Stream Events to Kafka

```yaml
# application.properties
kafka.bootstrap.servers=localhost:9092
mp.messaging.outgoing.workflow-events.connector=smallrye-kafka
mp.messaging.outgoing.workflow-events.topic=workflow-events
```

```java
@ApplicationScoped
public class WorkflowEventPublisher {
  
  @Inject
  @Channel("workflow-events")
  Emitter<WorkflowEvent> emitter;
  
  @Inject
  WorkflowExecutionService executionService;
  
  public void start() {
    // Subscribe to all workflow events
    executionService.watchAllExecutions()
      .onItem().invoke(event -> {
        // Enrich event
        EnrichedEvent enriched = 
          EnrichedEvent.from(event)
            .timestamp(Instant.now())
            .build();
        
        emitter.send(enriched);
      });
  }
}
```

### 8. Query Metrics

```bash
# Get workflow success rate
curl 'http://localhost:9090/api/v1/query?query=' \
  'rate(workflow_executions_total{status="success"}[5m])'

# Get p99 latency
curl 'http://localhost:9090/api/v1/query?query=' \
  'histogram_quantile(0.99, workflow_duration_ms)'

# Get active workflows
curl 'http://localhost:9090/api/v1/query?query=' \
  'workflow_active'
```

## Key Metrics to Monitor

### Workflow Metrics
```
workflow.executions_total
  - Counter: total workflow executions
  - Tags: workflow_id, status (success, failed, cancelled)
  - Alert: status=failed > 5%

workflow.duration_ms
  - Histogram: execution time
  - Percentiles: p50, p95, p99
  - Alert: p99 > SLA threshold

workflow.active
  - Gauge: currently running workflows
  - Alert: > max_concurrent threshold
```

### Node Metrics
```
node.duration_ms
  - Histogram per node
  - Tags: workflow_id, node_id, status
  - Alert: slow nodes

node.failures_total
  - Counter: failed node executions
  - Tags: workflow_id, node_id, error_type
  - Alert: failure rate spike
```

### Compensation Metrics
```
workflow.compensations_total
  - Counter: compensation executions
  - Tags: workflow_id, status (success, failed)
  - Alert: compensation failures > 10%

compensation.duration_ms
  - Histogram: time to complete compensation
  - Alert: > max timeout
```

### Service Integration Metrics
```
executor.latency_ms
  - Histogram per executor
  - Tags: executor_id, status
  - Alert: executor slow/down

executor.errors_total
  - Counter: executor failures
  - Tags: executor_id, error_type
  - Alert: error rate spike
```

## Example: Complete Monitoring Setup

```java
@ApplicationScoped
@Path("/api/workflows")
public class MonitoredWorkflowService {
  
  @Inject MeterRegistry meterRegistry;
  @Inject Logger log;
  @Inject WorkflowEventPublisher eventPublisher;
  @Inject WorkflowExecutionService executionService;
  
  @POST
  @Traced
  public Uni<WorkflowExecutionResult> executeWorkflow(
      WorkflowExecutionRequest request) {
    
    Timer.Sample sample = Timer.start(meterRegistry);
    Span span = Tracer.getCurrentSpan();
    
    // Set trace attributes
    span.setAttributes(Attributes.of(
      AttributeKey.stringKey("workflow_id"), 
        request.getWorkflowId(),
      AttributeKey.stringKey("execution_id"), 
        request.getExecutionId(),
      AttributeKey.stringKey("correlation_id"), 
        request.getCorrelationId()
    ));
    
    // Log start
    log.infof(
      "Workflow started: workflow=%s, execution=%s",
      request.getWorkflowId(),
      request.getExecutionId()
    );
    
    // Execute
    return executionService.execute(request)
      .onItem().invoke(result -> {
        // Record success metrics
        sample.stop(meterRegistry.timer(
          "workflow.duration",
          "workflow", request.getWorkflowId(),
          "status", "success"
        ));
        
        meterRegistry.counter("workflow.executions",
          "workflow", request.getWorkflowId(),
          "status", "success"
        ).increment();
        
        // Publish event
        eventPublisher.publishEvent(request, result);
        
        // Log completion
        log.infof(
          "Workflow completed: workflow=%s, " +
          "execution=%s, duration=%dms",
          request.getWorkflowId(),
          request.getExecutionId(),
          result.getDuration().toMillis()
        );
      })
      .onFailure().invoke(error -> {
        // Record error metrics
        sample.stop(meterRegistry.timer(
          "workflow.duration",
          "workflow", request.getWorkflowId(),
          "status", "failure"
        ));
        
        meterRegistry.counter("workflow.failures",
          "workflow", request.getWorkflowId(),
          "error", 
            error.getClass().getSimpleName()
        ).increment();
        
        // Log error
        log.errorf(error,
          "Workflow failed: workflow=%s, " +
          "execution=%s",
          request.getWorkflowId(),
          request.getExecutionId()
        );
      });
  }
  
  // Monitor node execution
  private void monitorNodeExecution(
      WorkflowEvent event) {
    
    if (event.getType() == 
        WorkflowEventType.NODE_COMPLETED) {
      
      meterRegistry.timer("node.duration",
        "workflow", event.getWorkflowId(),
        "node", event.getNodeId(),
        "status", "success"
      ).record(event.getDuration());
      
    } else if (event.getType() == 
        WorkflowEventType.NODE_FAILED) {
      
      meterRegistry.counter("node.failures",
        "node", event.getNodeId(),
        "error", event.getError()
      ).increment();
    }
  }
}
```

## Grafana Dashboards

Create dashboards showing:

1. **Workflow Overview**
   - Success/failure rates
   - Active workflows
   - Average duration

2. **Node Performance**
   - Latency by node
   - Error rates
   - Throughput

3. **Executor Health**
   - Response times per executor
   - Error rates
   - Availability

4. **Compensation Tracking**
   - Compensation success rate
   - Duration
   - Failed compensations

## Alerting Rules

```yaml
groups:
  - name: workflow-gamelan
    interval: 1m
    rules:
      - alert: HighWorkflowFailureRate
        expr: rate(workflow_failures[5m]) > 0.05
        for: 5m
        annotations:
          summary: "High workflow failure rate"
      
      - alert: SlowWorkflowExecution
        expr: histogram_quantile(0.99, 
          workflow_duration_ms) > 30000
        for: 10m
      
      - alert: CompensationFailure
        expr: rate(workflow_compensation_failures[5m]) > 0
        for: 1m
      
      - alert: ExecutorDown
        expr: executor_health == 0
        for: 2m
      
      - alert: TooManyActiveWorkflows
        expr: workflow_active > 1000
        for: 5m
```

## Best Practices

1. **Always Enable Tracing** - Understand request flow
2. **Monitor SLAs** - Track against objectives
3. **Use Correlation IDs** - Link related events
4. **Archive Logs** - Long-term retention
5. **Alert Strategically** - Avoid alert fatigue
6. **Test Dashboards** - Verify queries
7. **Review Metrics** - Understand patterns

## Troubleshooting

### Missing Traces
- Check Jaeger endpoint
- Verify OTLP exporter
- Check network connectivity

### Metrics Not Appearing
- Verify Prometheus scrape
- Check metric names
- Ensure endpoint accessible

### Performance Impact
- Monitor overhead
- Adjust sampling if needed
- Optimize metrics cardinality

## See Also

- [Execute Workflow](./execute-workflow.md)
- [Handle Saga Compensation](./handle-saga-compensation.md)
- [Observability Setup](../references/observability.md)
