# Workflow Gamelan Deployment Guide

## Production Deployment Guide

This guide covers the complete deployment process for the optimized workflow-gamelan engine.

---

## Table of Contents

1. [Prerequisites](#prerequisites)
2. [Configuration](#configuration)
3. [Docker Deployment](#docker-deployment)
4. [Kubernetes Deployment](#kubernetes-deployment)
5. [Post-Deployment Validation](#post-deployment-validation)
6. [Monitoring Setup](#monitoring-setup)
7. [Troubleshooting](#troubleshooting)

---

## Prerequisites

### Infrastructure Requirements

**Minimum:**
- CPU: 4 cores
- Memory: 8 GB RAM
- Storage: 20 GB SSD
- Network: 1 Gbps

**Recommended:**
- CPU: 8+ cores
- Memory: 16+ GB RAM
- Storage: 50+ GB SSD
- Network: 10 Gbps

### Software Requirements

- Java 21+ or GraalVM Native Image
- PostgreSQL 14+
- Redis 7+
- Kafka 3+ (optional)
- Kubernetes 1.25+ (for K8s deployment)
- Docker 20.10+ (for container deployment)

### Database Setup

```sql
-- Create database
CREATE DATABASE gamelan_engine;

-- Create user
CREATE USER gamelan_user WITH PASSWORD 'change-me';
GRANT ALL PRIVILEGES ON DATABASE gamelan_engine TO gamelan_user;

-- Enable required extensions
\c gamelan_engine
CREATE EXTENSION IF NOT EXISTS pg_stat_statements;
CREATE EXTENSION IF NOT EXISTS btree_gin;

-- Create indexes for JSONB optimization
CREATE INDEX idx_workflow_runs_context 
ON workflow_runs 
USING gin (context_variables);

CREATE INDEX idx_workflow_runs_nodes 
ON workflow_runs 
USING gin (node_executions);

CREATE INDEX idx_workflow_events_run_id 
ON workflow_events (run_id);
```

### Redis Setup

```bash
# Install Redis
apt-get install redis-server

# Configure Redis for streams
cat >> /etc/redis/redis.conf << EOF
# Stream configuration
stream-node-max-bytes 4096
stream-node-max-entries 100

# Memory management
maxmemory 2gb
maxmemory-policy allkeys-lru

# Persistence
appendonly yes
appendfsync everysec
EOF

# Restart Redis
systemctl restart redis-server
```

---

## Configuration

### Environment Variables

```bash
# Database
export DATABASE_URL=jdbc:postgresql://localhost:5432/gamelan_engine
export DATABASE_USER=gamelan_user
export DATABASE_PASSWORD=change-me
export DATABASE_MAX_POOL_SIZE=32

# Redis
export REDIS_URL=redis://localhost:6379
export REDIS_MAX_POOL_SIZE=16

# Kafka (optional)
export KAFKA_BOOTSTRAP_SERVERS=localhost:9092
export KAFKA_BATCH_SIZE=65536
export KAFKA_LINGER_MS=20

# Application
export GAMLAN_MAX_CONCURRENT_EXECUTIONS=5000
export GAMLAN_BACKPRESSURE_BUFFER_SIZE=1024
export GAMLAN_SNAPSHOT_FREQUENCY=50

# JVM Options
export JAVA_OPTS="-Xms2g -Xmx8g -XX:+UseG1GC -XX:MaxGCPauseMillis=100"

# Native Image (optional)
export QUARKUS_NATIVE_ENABLED=true
```

### Configuration File

Copy the production configuration:

```bash
cp core/gamelan-engine/src/main/resources/application-production.properties \
   core/gamelan-engine/src/main/resources/application.properties
```

Adjust values as needed for your environment.

---

## Docker Deployment

### Build Docker Image

```bash
# JVM Mode
docker build -t workflow-gamelan:latest .

# Native Mode
docker build --build-arg NATIVE=true -t workflow-gamelan:native .
```

### Docker Compose

Create `docker-compose.yml`:

```yaml
version: '3.8'

services:
  gamelan-engine:
    image: workflow-gamelan:latest
    ports:
      - "8080:8080"
      - "9000:9000"
    environment:
      - DATABASE_URL=jdbc:postgresql://postgres:5432/gamelan_engine
      - DATABASE_USER=gamelan_user
      - DATABASE_PASSWORD=change-me
      - REDIS_URL=redis://redis:6379
      - JAVA_OPTS=-Xms2g -Xmx8g -XX:+UseG1GC
    depends_on:
      - postgres
      - redis
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:8080/health/ready"]
      interval: 30s
      timeout: 10s
      retries: 3

  postgres:
    image: postgres:14
    environment:
      - POSTGRES_DB=gamelan_engine
      - POSTGRES_USER=gamelan_user
      - POSTGRES_PASSWORD=change-me
    volumes:
      - postgres-data:/var/lib/postgresql/data
      - ./scripts/init-db.sql:/docker-entrypoint-initdb.d/init.sql
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U gamelan_user"]
      interval: 10s
      timeout: 5s
      retries: 5

  redis:
    image: redis:7-alpine
    volumes:
      - redis-data:/data
    command: redis-server --appendonly yes
    healthcheck:
      test: ["CMD", "redis-cli", "ping"]
      interval: 10s
      timeout: 5s
      retries: 5

volumes:
  postgres-data:
  redis-data:
```

### Run with Docker Compose

```bash
# Start all services
docker-compose up -d

# Check status
docker-compose ps

# View logs
docker-compose logs -f gamelan-engine

# Stop all services
docker-compose down
```

---

## Kubernetes Deployment

### Create Namespace

```bash
kubectl create namespace gamelan-production
```

### Create Secrets

```bash
# Database credentials
kubectl create secret generic gamelan-db-credentials \
  --from-literal=username=gamelan_user \
  --from-literal=password=change-me \
  -n gamelan-production

# Redis credentials (if needed)
kubectl create secret generic gamelan-redis-credentials \
  --from-literal=password=change-me \
  -n gamelan-production
```

### Create ConfigMap

```bash
kubectl create configmap gamelan-config \
  --from-file=application.properties=core/gamelan-engine/src/main/resources/application-production.properties \
  -n gamelan-production
```

### Deploy Application

```bash
# Apply Kubernetes manifests
kubectl apply -f deployment/kubernetes/

# Check deployment status
kubectl get deployments -n gamelan-production

# Check pod status
kubectl get pods -n gamelan-production

# View logs
kubectl logs -f deployment/gamelan-engine -n gamelan-production
```

### Auto-Scaling

```bash
# Check HPA status
kubectl get hpa -n gamelan-production

# Manually scale
kubectl scale deployment gamelan-engine --replicas=10 -n gamelan-production

# View autoscaling events
kubectl get events -n gamelan-production --field-selector reason=SuccessfulRescale
```

---

## Post-Deployment Validation

### Health Checks

```bash
# Liveness probe
curl http://localhost:8080/health/live

# Readiness probe
curl http://localhost:8080/health/ready

# Startup probe
curl http://localhost:8080/health/started
```

### Run Benchmarks

```bash
# Run performance benchmarks
./scripts/benchmark-performance.sh \
  --duration 60 \
  --concurrency 10 \
  --nodes 100 \
  --output ./benchmark-results

# Review results
cat ./benchmark-results/summary.md
```

### Validate Configuration

```bash
# Check database connection
curl http://localhost:8080/api/health/database

# Check Redis connection
curl http://localhost:8080/api/health/redis

# Check Kafka connection (if enabled)
curl http://localhost:8080/api/health/kafka
```

---

## Monitoring Setup

### Prometheus Configuration

Add to `prometheus.yml`:

```yaml
scrape_configs:
  - job_name: 'gamelan-engine'
    static_configs:
      - targets: ['gamelan-engine.gamelan-production.svc:8080']
    metrics_path: '/metrics'
    scrape_interval: 15s
```

### Key Metrics to Monitor

```prometheus
# Workflow execution
gamelan_workflow_runs_total
gamelan_workflow_runs_active
gamelan_workflow_execution_duration_seconds

# Task queue
gamelan_task_queue_length
gamelan_task_queue_pending
gamelan_task_queue_processing_rate

# Database
gamelan_db_query_duration_seconds
gamelan_db_jsonb_update_duration_seconds
gamelan_db_connections_active

# Redis
gamelan_redis_stream_length
gamelan_redis_consumer_lag
gamelan_redis_lock_acquisitions_total

# JVM (if not native)
jvm_memory_used_bytes
jvm_gc_pause_seconds
jvm_threads_live
```

### Grafana Dashboard

Import the dashboard from `benchmark-results/grafana-dashboard.json` or create custom panels.

**Recommended Panels:**
1. Throughput (req/s)
2. Latency (P95, P99)
3. Error Rate
4. Resource Utilization
5. Task Queue Length
6. Database Connections
7. Redis Stream Lag

---

## Troubleshooting

### High Database Latency

**Symptoms:**
- `gamelan_db_query_duration_seconds` > 100ms
- Slow JSONB updates

**Solutions:**
```sql
-- Check JSONB size
SELECT run_id, pg_column_size(context_variables) as size
FROM workflow_runs
ORDER BY size DESC
LIMIT 10;

-- Add missing indexes
CREATE INDEX IF NOT EXISTS idx_workflow_runs_context 
ON workflow_runs USING gin (context_variables);

-- Analyze table statistics
ANALYZE workflow_runs;
ANALYZE workflow_events;
```

### Redis Consumer Lag

**Symptoms:**
- Growing `gamelan_redis_consumer_lag`
- Tasks not being processed

**Solutions:**
```bash
# Check consumer lag
redis-cli XPENDING gamelan:task-queue gamelan-executors - + 100

# Scale consumers
kubectl scale deployment gamelan-executor --replicas=10 -n gamelan-production

# Cleanup stuck tasks
redis-cli XCLAIM gamelan:task-queue gamelan-executors reclaimer 3600000 <task-id>
```

### High Memory Usage

**Symptoms:**
- `jvm_memory_used_bytes` approaching limit
- OOM errors

**Solutions:**
```properties
# Increase heap size
quarkus.jvm.additional-args=-Xmx16g

# Switch to Shenandoah GC
quarkus.jvm.additional-args=-XX:+UseShenandoahGC

# Enable native image
quarkus.native.enabled=true
```

### High Error Rate

**Symptoms:**
- `gamelan_workflow_errors_total` increasing
- Success rate < 99%

**Solutions:**
```bash
# Check error logs
kubectl logs -f deployment/gamelan-engine -n gamelan-production | grep ERROR

# Check resource limits
kubectl describe pod -n gamelan-production

# Increase timeouts
gamelan.workflow.execution.timeout=600000
gamelan.saga.compensation.timeout=60000
```

---

## Resources

- [Performance Tuning Guide](docs/PERFORMANCE_TUNING_GUIDE.md)
- [Enhancement Summary](ENHANCEMENT_SUMMARY.md)
- [Production Configuration](core/gamelan-engine/src/main/resources/application-production.properties)
- [Benchmark Script](scripts/benchmark-performance.sh)

---

**Last Updated:** March 17, 2026  
**Version:** 2.0.0
