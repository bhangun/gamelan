# Gamelan Registry Agents Guide

This document describes how the Gamelan Registry module can be leveraged by AI agents, automated systems, and orchestration tools to manage workflow executors effectively.

## Overview

The Gamelan Registry serves as the central discovery and coordination point for workflow executors in distributed environments. AI agents and automation systems can leverage the registry to make intelligent decisions about executor allocation, scaling, and health management.

## Agent Integration Patterns

### 1. Auto-Scaling Agent

An auto-scaling agent monitors the registry statistics and automatically provisions or deprovisions executors based on demand:

```java
@ApplicationScoped
public class AutoScalingAgent {
    
    @Inject
    ExecutorRegistry registry;
    
    @Inject
    ExecutorProvisioner provisioner; // Hypothetical provisioning service
    
    @Scheduled(every = "30s")
    void evaluateScalingNeeds() {
        registry.getStatistics()
            .subscribe().with(stats -> {
                int healthyCount = stats.healthyExecutors();
                int totalTasks = getPendingTaskCount(); // From workflow engine
                
                double utilization = (double) totalTasks / (healthyCount * AVERAGE_TASKS_PER_EXECUTOR);
                
                if (utilization > 0.8) {
                    // Scale up
                    int needed = (int) Math.ceil(totalTasks / AVERAGE_TASKS_PER_EXECUTOR) - healthyCount;
                    provisioner.provisionExecutors(needed);
                } else if (utilization < 0.3 && healthyCount > MIN_EXECUTORS) {
                    // Scale down
                    int excess = healthyCount - (int) Math.ceil(totalTasks / AVERAGE_TASKS_PER_EXECUTOR);
                    if (excess > 0) {
                        provisioner.terminateExecutors(excess);
                    }
                }
            });
    }
}
```

### 2. Health Monitoring Agent

A health monitoring agent tracks executor health and takes corrective actions:

```java
@ApplicationScoped
public class HealthMonitoringAgent {
    
    @Inject
    ExecutorRegistry registry;
    
    @Inject
    NotificationService notifications;
    
    @Scheduled(every = "10s")
    void monitorHealth() {
        registry.getStatistics()
            .subscribe().with(stats -> {
                if (stats.unhealthyExecutors() > 0) {
                    notifications.sendAlert("Unhealthy executors detected: " + stats.unhealthyExecutors());
                    
                    // Attempt to restart unhealthy executors
                    restartUnhealthyExecutors();
                }
            });
    }
    
    private void restartUnhealthyExecutors() {
        // Implementation to restart unhealthy executors
    }
}
```

### 3. Load Balancing Agent

A load balancing agent optimizes executor selection based on real-time metrics:

```java
@ApplicationScoped
public class LoadBalancingAgent {
    
    @Inject
    ExecutorRegistry registry;
    
    void optimizeSelectionStrategy() {
        registry.getStatistics()
            .subscribe().with(stats -> {
                if (hasHighLoadVariation(stats)) {
                    // Switch to weighted strategy for better load distribution
                    registry.setSelectionStrategy(new WeightedSelectionStrategy());
                } else {
                    // Use round-robin for even distribution
                    registry.setSelectionStrategy(new RoundRobinSelectionStrategy());
                }
            });
    }
    
    private boolean hasHighLoadVariation(ExecutorStatistics stats) {
        // Logic to determine if load is unevenly distributed
        return false;
    }
}
```

## API Endpoints for Agent Integration

The registry provides programmatic access through its service interface:

### Core Operations

| Operation | Method | Purpose |
|-----------|--------|---------|
| Register Executor | `registerExecutor()` | Add new executor to registry |
| Discover Executor | `getExecutorForNode()` | Find suitable executor for workflow node |
| Heartbeat | `heartbeat()` | Update executor health status |
| Get Statistics | `getStatistics()` | Retrieve registry metrics |
| Query Executors | `getExecutorsBy*()` | Filter executors by attributes |

### Health Status

Agents can monitor individual executor health:

```java
// Check specific executor health
registry.isHealthy("executor-123")
    .subscribe().with(healthy -> {
        if (!healthy) {
            // Take remedial action
        }
    });

// Get health details
registry.getHealthInfo("executor-123")
    .subscribe().with(healthOpt -> {
        if (healthOpt.isPresent()) {
            var health = healthOpt.get();
            System.out.println("Last heartbeat: " + health.lastHeartbeat);
            System.out.println("Registered at: " + health.registeredAt);
        }
    });
```

## Agent Configuration

### Environment Variables

Agents can be configured using standard environment variables:

```bash
# Registry connection settings
GAMELAN_REGISTRY_HOST=localhost
GAMELAN_REGISTRY_PORT=8080

# Health thresholds
GAMELAN_HEALTH_THRESHOLD_SECONDS=30
GAMELAN_AGENT_POLL_INTERVAL_MS=10000

# Scaling policies
GAMELAN_MIN_EXECUTORS=2
GAMELAN_MAX_EXECUTORS=50
GAMELAN_TARGET_UTILIZATION=0.75
```

### Configuration Example

```yaml
agents:
  autoscaler:
    enabled: true
    pollInterval: "30s"
    minExecutors: 2
    maxExecutors: 100
    targetUtilization: 0.75
    scaleUpThreshold: 0.8
    scaleDownThreshold: 0.3
  
  healthMonitor:
    enabled: true
    pollInterval: "10s"
    alertThreshold: 1 # Number of unhealthy executors to trigger alert
    autoRestart: true
  
  loadBalancer:
    enabled: true
    strategy: "weighted" # round-robin, random, weighted
    optimizationInterval: "60s"
```

## Best Practices for Agent Development

### 1. Idempotent Operations

Ensure agent operations are idempotent to handle retries gracefully:

```java
public Uni<Void> safelyRegisterExecutor(ExecutorInfo executor) {
    return registry.getExecutorById(executor.executorId())
        .flatMap(existing -> {
            if (existing.isPresent()) {
                // Already registered, update instead
                return registry.updateExecutorMetadata(
                    executor.executorId(), 
                    executor.metadata()
                );
            } else {
                // Register new executor
                return registry.registerExecutor(executor);
            }
        });
}
```

### 2. Rate Limiting

Implement rate limiting to prevent overwhelming the registry:

```java
@ApplicationScoped
public class RateLimitedRegistryClient {
    
    private final Semaphore semaphore = new Semaphore(10); // Max 10 concurrent requests
    
    public Uni<T> withRateLimit(Supplier<Uni<T>> operation) {
        return Uni.createFrom().emitter(emitter -> {
            semaphore.acquireUninterruptibly();
            try {
                operation.get()
                    .subscribe()
                    .with(
                        emitter::complete,
                        emitter::fail
                    );
            } finally {
                semaphore.release();
            }
        });
    }
}
```

### 3. Circuit Breaking

Use circuit breakers to handle registry failures gracefully:

```java
@ApplicationScoped
public class ResilientRegistryClient {
    
    @CircuitBreaker(
        requestVolumeThreshold = 5,
        failureRatio = 0.5,
        delay = 10000
    )
    public Uni<Optional<ExecutorInfo>> getExecutorWithFallback(NodeId nodeId) {
        return registry.getExecutorForNode(nodeId)
            .onFailure().recoverWithItem(Optional.empty());
    }
}
```

## Security Considerations

### Authentication

Agents should authenticate with the registry using appropriate credentials:

```java
// Using JWT tokens for authentication
public class AuthenticatedRegistryClient {
    
    public Uni<ExecutorInfo> getExecutorSecurely(NodeId nodeId, String authToken) {
        // Add authentication headers or validate token
        return registry.getExecutorForNode(nodeId);
    }
}
```

### Authorization

Ensure agents only perform authorized operations:

- Registration agents should only register executors they manage
- Monitoring agents should have read-only access where possible
- Scaling agents should have appropriate permissions to modify infrastructure

## Monitoring and Observability

### Metrics Collection

Agents should emit their own metrics for observability:

```java
@ApplicationScoped
public class MonitoredAgent {
    
    @Inject
    MeterRegistry meterRegistry;
    
    void initializeMetrics() {
        Gauge.builder("agent.scaling.decisions")
            .register(meterRegistry, this, agent -> agent.scalingDecisions);
            
        Counter scalingActions = Counter.builder("agent.scaling.actions")
            .register(meterRegistry);
    }
}
```

### Logging

Implement structured logging for debugging:

```java
private static final Logger LOG = LoggerFactory.getLogger(AutoScalingAgent.class);

void logScalingDecision(int current, int target, String reason) {
    LOG.info("Scaling decision: {} -> {} executors (reason: {})", 
             current, target, reason);
}
```

## Integration Examples

### Kubernetes Operator

A Kubernetes operator can use the registry to manage executor pods:

```java
@Singleton
public class ExecutorOperator {
    
    @Inject
    ExecutorRegistry registry;
    
    @Inject
    KubernetesClient k8sClient;
    
    void reconcileExecutorPods() {
        registry.getStatistics()
            .subscribe().with(stats -> {
                int desiredCount = calculateDesiredExecutorCount(stats);
                
                // Scale Kubernetes deployment
                k8sClient.apps().deployments()
                    .inNamespace("gamelan")
                    .withName("executor-deployment")
                    .scale(desiredCount);
            });
    }
}
```

### Cloud Provider Integration

Integrate with cloud providers for auto-scaling:

```java
@ApplicationScoped
public class CloudAutoScaler {
    
    @Inject
    ExecutorRegistry registry;
    
    void scaleBasedOnCloudMetrics() {
        // Get cloud provider metrics
        var cloudMetrics = getCloudProviderMetrics();
        
        registry.getStatistics()
            .subscribe().with(registryStats -> {
                // Combine registry and cloud metrics for scaling decision
                var combinedMetrics = combineMetrics(cloudMetrics, registryStats);
                makeScalingDecision(combinedMetrics);
            });
    }
}
```

## Troubleshooting

### Common Issues

1. **Registry Overload**: Implement proper rate limiting in agents
2. **Stale Data**: Use appropriate cache invalidation strategies
3. **Network Partitions**: Implement circuit breakers and fallbacks
4. **Authentication Failures**: Ensure proper credential rotation

### Debugging Tips

- Enable DEBUG logging for registry operations
- Monitor registry metrics for anomalies
- Check agent-specific metrics for performance issues
- Review audit logs for unauthorized access attempts

## Future Enhancements

### Planned Features

- **Predictive Scaling**: ML-based scaling predictions
- **Multi-Region Support**: Cross-region executor discovery
- **Cost Optimization**: Cost-aware executor placement
- **Advanced Scheduling**: Priority-based executor allocation

### Roadmap

- Q1: Enhanced security with mTLS
- Q2: Multi-cloud registry federation
- Q3: AI-powered optimization algorithms
- Q4: Advanced analytics dashboard

---

For more information about the Gamelan platform, refer to the main documentation.