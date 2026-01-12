package tech.kayys.silat.dto;

import java.util.Map;

import tech.kayys.silat.model.ErrorInfo;
import tech.kayys.silat.model.NodeExecution;
import tech.kayys.silat.model.WorkflowRun;
import tech.kayys.silat.model.WorkflowRunSnapshot;

/**
 * Maps domain objects to DTOs
 */
@jakarta.enterprise.context.ApplicationScoped
public class RunResponseMapper {
    
    /**
     * Converts a WorkflowRun to a RunResponse DTO
     * @param run the workflow run to convert
     * @return the corresponding RunResponse
     */
    public RunResponse toResponse(WorkflowRun run) {
        if (run == null) {
            return null;
        }
        
        WorkflowRunSnapshot snapshot = run.createSnapshot();
        
        Map<String, NodeExecutionDto> nodeExecutionDtos = snapshot.nodeExecutions().entrySet()
            .stream()
            .collect(java.util.stream.Collectors.toMap(
                entry -> entry.getKey().value(),
                entry -> toNodeExecutionDto(entry.getValue())
            ));
        
        Long durationMs = null;
        if (snapshot.startedAt() != null && snapshot.completedAt() != null) {
            durationMs = java.time.Duration.between(
                snapshot.startedAt(), 
                snapshot.completedAt()
            ).toMillis();
        }
        
        return new RunResponse(
            snapshot.id().value(),
            snapshot.tenantId().value(),
            snapshot.definitionId().value(),
            null, // version - simplified
            snapshot.status().name(),
            snapshot.variables(),
            nodeExecutionDtos,
            snapshot.executionPath(),
            snapshot.createdAt(),
            snapshot.startedAt(),
            snapshot.completedAt(),
            durationMs,
            Map.of(), // labels - simplified
            Map.of()  // metadata - simplified
        );
    }
    
    /**
     * Converts a NodeExecution to a NodeExecutionDto
     * @param exec the node execution to convert
     * @return the corresponding NodeExecutionDto
     */
    private NodeExecutionDto toNodeExecutionDto(NodeExecution exec) {
        Long durationMs = null;
        if (exec.getStartedAt() != null && exec.getCompletedAt() != null) {
            durationMs = java.time.Duration.between(
                exec.getStartedAt(),
                exec.getCompletedAt()
            ).toMillis();
        }
        
        ErrorDto errorDto = null;
        if (exec.getLastError() != null) {
            ErrorInfo error = exec.getLastError();
            errorDto = new ErrorDto(
                error.code(),
                error.message(),
                error.stackTrace(),
                error.context()
            );
        }
        
        return new NodeExecutionDto(
            exec.getNodeId().value(),
            null, // node name - would need to lookup from definition
            exec.getStatus().name(),
            exec.getAttempt(),
            exec.getStartedAt(),
            exec.getCompletedAt(),
            durationMs,
            exec.getOutput(),
            errorDto
        );
    }
}
