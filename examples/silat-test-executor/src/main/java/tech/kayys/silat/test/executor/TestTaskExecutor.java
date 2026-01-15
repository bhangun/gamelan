package tech.kayys.silat.test.executor;

import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import tech.kayys.silat.sdk.executor.AbstractWorkflowExecutor;
import tech.kayys.silat.sdk.executor.SimpleNodeExecutionResult;
import tech.kayys.silat.execution.NodeExecutionTask;

import java.util.Map;

import tech.kayys.silat.sdk.executor.Executor;
import tech.kayys.silat.model.CommunicationType;

@ApplicationScoped
@Executor(executorType = "test-executor", maxConcurrentTasks = 10, supportedNodeTypes = {
        "simple-test-task", "step1", "step2" }, communicationType = CommunicationType.REST
)
public class TestTaskExecutor extends AbstractWorkflowExecutor {

    @Override
    public Uni<tech.kayys.silat.execution.NodeExecutionResult> execute(NodeExecutionTask task) {
        String nodeType = extractNodeType(task);
        System.out.println("==================================================");
        System.out.println("EXECUTOR: Processing Node [" + task.nodeId() + "] of type [" + nodeType + "]");
        System.out.println("RUN ID: " + task.runId());
        System.out.println("ATTEMPT: " + task.attempt());
        System.out.println("CONTEXT: " + task.context());

        // Simulate varying processing latency (100ms - 1000ms)
        long latency = 100 + (long) (Math.random() * 900);
        
        // Simulate random failure (15% chance)
        boolean shouldFail = Math.random() < 0.15;

        return Uni.createFrom().item(() -> {
            try {
                Thread.sleep(latency);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            if (shouldFail) {
                System.out.println("!!! SIMULATED FAILURE for node: " + task.nodeId());
                return SimpleNodeExecutionResult.failure(
                    task.runId(),
                    task.nodeId(),
                    task.attempt(),
                    new tech.kayys.silat.model.ErrorInfo(
                        "SIMULATED_ERROR", 
                        "Realistic simulation triggered a failure", 
                        "TestTaskExecutor", 
                        Map.of("latency", latency)
                    ),
                    task.token()
                );
            }

            // Meaningful outputs based on node type
            Map<String, Object> outputs = new java.util.HashMap<>();
            outputs.put("processedBy", "RealisticTestExecutor");
            outputs.put("processingTimeMs", latency);
            outputs.put("completionTimestamp", java.time.Instant.now().toString());

            if ("step1".equals(nodeType)) {
                outputs.put("step1Result", "validated");
                outputs.put("nextAction", "proceed_to_step2");
            } else if ("step2".equals(nodeType)) {
                outputs.put("step2Result", "finalized");
                outputs.put("workflowStatus", "success");
            } else {
                outputs.put("genericResult", "processed_" + nodeType);
            }

            System.out.println(">>> SUCCESS processing node: " + task.nodeId());
            System.out.println(">>> OUTPUTS: " + outputs);

            return SimpleNodeExecutionResult.success(
                    task.runId(),
                    task.nodeId(),
                    task.attempt(),
                    outputs,
                    task.token(),
                    java.time.Duration.ofMillis(latency)
            );
        });
    }
}
