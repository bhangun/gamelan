package tech.kayys.gamelan.engine.node;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import tech.kayys.gamelan.engine.error.ErrorInfo;
import tech.kayys.gamelan.engine.execution.ExecutionContext;
import tech.kayys.gamelan.engine.execution.ExecutionError;
import tech.kayys.gamelan.engine.execution.ExecutionToken;
import tech.kayys.gamelan.engine.run.WaitInfo;
import tech.kayys.gamelan.engine.workflow.WorkflowRunId;

/**
 * Node Execution Result - Result from executor
 */

public record DefaultNodeExecutionResult(
                WorkflowRunId runId,
                NodeId nodeId,
                int attempt,
                NodeExecutionStatus status,
                Map<String, Object> output,
                ErrorInfo error,
                ExecutionToken executionToken) implements NodeExecutionResult {

        @Override
        public NodeExecutionStatus getStatus() {
                return status;
        }

        @Override
        public String getNodeId() {
                return nodeId.value();
        }

        @Override
        public Instant getExecutedAt() {
                return Instant.now();
        }

        @Override
        public Duration getDuration() {
                return Duration.ZERO;
        }

        @Override
        public ExecutionContext getUpdatedContext() {
                return ExecutionContext.builder().variables(output).build();
        }

        @Override
        public ExecutionError getError() {
                if (error == null)
                        return null;
                // Simple mapping
                return new ExecutionError() {
                        @Override
                        public String getCode() {
                                return error.code();
                        }

                        @Override
                        public String getMessage() {
                                return error.message();
                        }

                        @Override
                        public Category getCategory() {
                                return Category.SYSTEM;
                        }

                        @Override
                        public boolean isRetriable() {
                                return false;
                        }

                        @Override
                        public String getCompensationHint() {
                                return null;
                        }

                        @Override
                        public Map<String, Object> getDetails() {
                                return error.context();
                        }
                };
        }

        @Override
        public WaitInfo getWaitInfo() {
                return null;
        }

        @Override
        public Map<String, Object> getMetadata() {
                return Map.of();
        }
}
