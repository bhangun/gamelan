#!/bin/bash

# =============================================================================
# Workflow Gamelan Performance Benchmark Script
# =============================================================================
# This script runs comprehensive performance benchmarks
# Usage: ./benchmark-performance.sh [options]
# Options:
#   --duration: Test duration in seconds (default: 60)
#   --concurrency: Number of concurrent workflows (default: 10)
#   --nodes: Number of nodes per workflow (default: 100)
#   --output: Output directory for results (default: ./benchmark-results)
# =============================================================================

set -e

# Default values
DURATION=60
CONCURRENCY=10
NODES=100
OUTPUT_DIR="./benchmark-results"
BASE_URL="http://localhost:8080"

# Parse arguments
while [[ $# -gt 0 ]]; do
    case $1 in
        --duration)
            DURATION="$2"
            shift 2
            ;;
        --concurrency)
            CONCURRENCY="$2"
            shift 2
            ;;
        --nodes)
            NODES="$2"
            shift 2
            ;;
        --output)
            OUTPUT_DIR="$2"
            shift 2
            ;;
        *)
            echo "Unknown option: $1"
            exit 1
            ;;
    esac
done

# Create output directory
mkdir -p "$OUTPUT_DIR"

echo "============================================="
echo "Workflow Gamelan Performance Benchmark"
echo "============================================="
echo "Duration: ${DURATION}s"
echo "Concurrency: ${CONCURRENCY}"
echo "Nodes per workflow: ${NODES}"
echo "Output directory: ${OUTPUT_DIR}"
echo "============================================="
echo ""

# Install hey if not present
if ! command -v hey &> /dev/null; then
    echo "Installing hey (HTTP load generator)..."
    go install github.com/rakyll/hey@latest
fi

# Test 1: Workflow Creation Throughput
echo "Test 1: Workflow Creation Throughput..."
echo "-----------------------------------------"

hey -z ${DURATION}s \
    -c ${CONCURRENCY} \
    -m POST \
    -T "application/json" \
    -d '{"name":"benchmark-workflow","nodes":'${NODES}'}' \
    -o csv \
    "${BASE_URL}/api/workflows" > "${OUTPUT_DIR}/workflow-creation.csv"

echo "✓ Workflow creation test complete"
echo ""

# Test 2: Workflow Execution Latency
echo "Test 2: Workflow Execution Latency..."
echo "-----------------------------------------"

hey -z ${DURATION}s \
    -c ${CONCURRENCY} \
    -m POST \
    -T "application/json" \
    -d '{"workflowId":"test-workflow","context":{}}' \
    -o csv \
    "${BASE_URL}/api/workflows/execute" > "${OUTPUT_DIR}/workflow-execution.csv"

echo "✓ Workflow execution test complete"
echo ""

# Test 3: Task Queue Throughput
echo "Test 3: Task Queue Throughput..."
echo "-----------------------------------------"

hey -z ${DURATION}s \
    -c ${CONCURRENCY} \
    -m POST \
    -T "application/json" \
    -d '{"taskId":"test-task","payload":{}}' \
    -o csv \
    "${BASE_URL}/api/tasks" > "${OUTPUT_DIR}/task-queue.csv"

echo "✓ Task queue test complete"
echo ""

# Test 4: JSONB Update Performance
echo "Test 4: JSONB Update Performance..."
echo "-----------------------------------------"

hey -z ${DURATION}s \
    -c ${CONCURRENCY} \
    -m PUT \
    -T "application/json" \
    -d '{"key":"test-key","value":"test-value"}' \
    -o csv \
    "${BASE_URL}/api/workflows/test-id/context" > "${OUTPUT_DIR}/jsonb-update.csv"

echo "✓ JSONB update test complete"
echo ""

# Test 5: Redis Stream Performance
echo "Test 5: Redis Stream Performance..."
echo "-----------------------------------------"

hey -z ${DURATION}s \
    -c ${CONCURRENCY} \
    -m POST \
    -T "application/json" \
    -d '{"stream":"test-stream","data":{}}' \
    -o csv \
    "${BASE_URL}/api/redis/stream" > "${OUTPUT_DIR}/redis-stream.csv"

echo "✓ Redis stream test complete"
echo ""

# Generate Summary Report
echo "Generating summary report..."
echo "-----------------------------------------"

cat > "${OUTPUT_DIR}/summary.md" << EOF
# Performance Benchmark Summary

**Date:** $(date)
**Duration:** ${DURATION}s
**Concurrency:** ${CONCURRENCY}
**Nodes per workflow:** ${NODES}

## Test Results

### Workflow Creation Throughput
\`\`\`
$(head -n 2 "${OUTPUT_DIR}/workflow-creation.csv" | tail -n 1 | awk -F',' '{printf "Requests/sec: %.2f\nAvg Latency: %.2fms\nP95 Latency: %.2fms\nP99 Latency: %.2fms", $2, $4*1000, $9*1000, $10*1000}')
\`\`\`

### Workflow Execution Latency
\`\`\`
$(head -n 2 "${OUTPUT_DIR}/workflow-execution.csv" | tail -n 1 | awk -F',' '{printf "Requests/sec: %.2f\nAvg Latency: %.2fms\nP95 Latency: %.2fms\nP99 Latency: %.2fms", $2, $4*1000, $9*1000, $10*1000}')
\`\`\`

### Task Queue Throughput
\`\`\`
$(head -n 2 "${OUTPUT_DIR}/task-queue.csv" | tail -n 1 | awk -F',' '{printf "Requests/sec: %.2f\nAvg Latency: %.2fms\nP95 Latency: %.2fms\nP99 Latency: %.2fms", $2, $4*1000, $9*1000, $10*1000}')
\`\`\`

### JSONB Update Performance
\`\`\`
$(head -n 2 "${OUTPUT_DIR}/jsonb-update.csv" | tail -n 1 | awk -F',' '{printf "Requests/sec: %.2f\nAvg Latency: %.2fms\nP95 Latency: %.2fms\nP99 Latency: %.2fms", $2, $4*1000, $9*1000, $10*1000}')
\`\`\`

### Redis Stream Performance
\`\`\`
$(head -n 2 "${OUTPUT_DIR}/redis-stream.csv" | tail -n 1 | awk -F',' '{printf "Requests/sec: %.2f\nAvg Latency: %.2fms\nP95 Latency: %.2fms\nP99 Latency: %.2fms", $2, $4*1000, $9*1000, $10*1000}')
\`\`\`

## Performance Targets

| Metric | Target | Actual | Status |
|--------|--------|--------|--------|
| Throughput | >1000 req/s | TBD | ⏳ |
| P95 Latency | <200ms | TBD | ⏳ |
| P99 Latency | <500ms | TBD | ⏳ |
| Success Rate | >99% | TBD | ⏳ |

## Recommendations

Based on the benchmark results:

1. **If throughput < target:**
   - Increase concurrency
   - Scale horizontally
   - Optimize database queries

2. **If latency > target:**
   - Enable caching
   - Optimize JSONB operations
   - Tune connection pool

3. **If success rate < target:**
   - Check error logs
   - Increase timeouts
   - Add retry logic

EOF

echo "✓ Summary report generated: ${OUTPUT_DIR}/summary.md"
echo ""

# Generate Grafana Dashboard JSON
echo "Generating Grafana dashboard..."
echo "-----------------------------------------"

cat > "${OUTPUT_DIR}/grafana-dashboard.json" << 'EOF'
{
  "dashboard": {
    "title": "Workflow Gamelan Performance",
    "panels": [
      {
        "title": "Throughput (req/s)",
        "type": "graph",
        "targets": [
          {
            "expr": "rate(gamelan_workflow_runs_total[1m])",
            "legendFormat": "Workflow Creation"
          },
          {
            "expr": "rate(gamelan_task_queue_total[1m])",
            "legendFormat": "Task Queue"
          }
        ]
      },
      {
        "title": "Latency (ms)",
        "type": "graph",
        "targets": [
          {
            "expr": "histogram_quantile(0.95, rate(gamelan_workflow_duration_seconds_bucket[1m]))",
            "legendFormat": "P95"
          },
          {
            "expr": "histogram_quantile(0.99, rate(gamelan_workflow_duration_seconds_bucket[1m]))",
            "legendFormat": "P99"
          }
        ]
      },
      {
        "title": "Error Rate",
        "type": "graph",
        "targets": [
          {
            "expr": "rate(gamelan_workflow_errors_total[1m]) / rate(gamelan_workflow_runs_total[1m])",
            "legendFormat": "Error Rate"
          }
        ]
      },
      {
        "title": "Resource Utilization",
        "type": "graph",
        "targets": [
          {
            "expr": "gamelan_db_connections_active",
            "legendFormat": "DB Connections"
          },
          {
            "expr": "gamelan_redis_connections_active",
            "legendFormat": "Redis Connections"
          }
        ]
      }
    ]
  }
}
EOF

echo "✓ Grafana dashboard generated: ${OUTPUT_DIR}/grafana-dashboard.json"
echo ""

echo "============================================="
echo "Benchmark Complete!"
echo "============================================="
echo ""
echo "Results saved to: ${OUTPUT_DIR}"
echo "  - workflow-creation.csv"
echo "  - workflow-execution.csv"
echo "  - task-queue.csv"
echo "  - jsonb-update.csv"
echo "  - redis-stream.csv"
echo "  - summary.md"
echo "  - grafana-dashboard.json"
echo ""
echo "Next steps:"
echo "  1. Review summary.md for results"
echo "  2. Import grafana-dashboard.json into Grafana"
echo "  3. Compare results with performance targets"
echo "  4. Apply optimizations as needed"
echo ""
