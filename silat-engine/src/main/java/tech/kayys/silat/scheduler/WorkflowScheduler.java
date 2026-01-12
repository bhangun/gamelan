package tech.kayys.silat.scheduler;

import io.smallrye.mutiny.Uni;
import tech.kayys.silat.execution.NodeExecutionTask;
import tech.kayys.silat.model.NodeId;
import tech.kayys.silat.model.WorkflowRunId;
import tech.kayys.silat.model.event.ExecutionEvent;

import java.time.Duration;
import java.util.List;

/**
 * ============================================================================
 * WORKFLOW SCHEDULER
 * ============================================================================
 * 
 * Responsibilities:
 * 1. Schedule node executions to executors
 * 2. Route tasks based on executor type
 * 3. Handle task retries and backoff
 * 4. Publish events to Kafka
 * 5. Dead letter queue management
 * 
 * Architecture:
 * - Priority-based task queue
 * - Distributed execution via Kafka/gRPC
 * - Exponential backoff for retries
 * - Circuit breaker for executor health
 */

public interface WorkflowScheduler {

    /**
     * Schedule a node execution task
     */
    Uni<Void> scheduleTask(NodeExecutionTask task);

    /**
     * Schedule a delayed retry
     */
    Uni<Void> scheduleRetry(
            WorkflowRunId runId,
            NodeId nodeId,
            Duration delay);

    /**
     * Cancel all tasks for a run
     */
    Uni<Void> cancelTasksForRun(WorkflowRunId runId);

    /**
     * Publish events to event bus
     */
    Uni<Void> publishEvents(List<ExecutionEvent> events);

    /**
     * Get scheduled tasks count
     */
    Uni<Long> getScheduledTasksCount();
}
