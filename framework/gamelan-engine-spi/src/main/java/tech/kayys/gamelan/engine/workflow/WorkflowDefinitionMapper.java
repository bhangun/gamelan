package tech.kayys.gamelan.engine.workflow;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import tech.kayys.gamelan.engine.io.dto.InputDefinitionDto;
import tech.kayys.gamelan.engine.io.dto.OutputDefinitionDto;
import tech.kayys.gamelan.engine.node.InputDefinition;
import tech.kayys.gamelan.engine.node.NodeDefinition;
import tech.kayys.gamelan.engine.node.OutputDefinition;
import tech.kayys.gamelan.engine.node.dto.NodeDefinitionDto;
import tech.kayys.gamelan.engine.run.RetryPolicy;
import tech.kayys.gamelan.engine.run.Transition;
import tech.kayys.gamelan.engine.run.dto.RetryPolicyDto;
import tech.kayys.gamelan.engine.saga.CompensationPolicy;
import tech.kayys.gamelan.engine.saga.dto.CompensationPolicyDto;
import tech.kayys.gamelan.engine.transition.dto.TransitionDto;
import tech.kayys.gamelan.engine.workflow.dto.CreateWorkflowDefinitionRequest;

/**
 * Maps domain objects to Request/Response DTOs
 */
public class WorkflowDefinitionMapper {

    public static CreateWorkflowDefinitionRequest toCreateRequest(WorkflowDefinition domain) {
        if (domain == null) {
            return null;
        }

        return new CreateWorkflowDefinitionRequest(
                domain.name(),
                domain.version(),
                domain.description(),
                mapNodes(domain.nodes()),
                mapInputs(domain.inputs()),
                mapOutputs(domain.outputs()),
                mapRetryPolicy(domain.defaultRetryPolicy()),
                mapCompensationPolicy(domain.compensationPolicy()),
                domain.metadata() != null ? domain.metadata().labels() : Map.of());
    }

    private static List<NodeDefinitionDto> mapNodes(List<NodeDefinition> nodes) {
        if (nodes == null) {
            return List.of();
        }
        return nodes.stream().map(WorkflowDefinitionMapper::mapNode).toList();
    }

    private static NodeDefinitionDto mapNode(NodeDefinition node) {
        if (node == null) {
            return null;
        }

        List<String> dependsOn = node.dependsOn() != null
                ? node.dependsOn().stream().map(id -> id.value()).toList()
                : List.of();

        List<TransitionDto> transitions = node.transitions() != null
                ? node.transitions().stream().map(WorkflowDefinitionMapper::mapTransition).toList()
                : List.of();

        return new NodeDefinitionDto(
                node.id().value(),
                node.name(),
                node.type().name(),
                node.executorType(),
                node.configuration(),
                dependsOn,
                transitions,
                mapRetryPolicy(node.retryPolicy()),
                node.timeout() != null ? node.timeout().toSeconds() : 30L,
                node.critical());
    }

    private static TransitionDto mapTransition(Transition domain) {
        if (domain == null) {
            return null;
        }
        return new TransitionDto(
                domain.targetNodeId() != null ? domain.targetNodeId().value() : null,
                domain.condition(),
                domain.type().name());
    }

    private static Map<String, InputDefinitionDto> mapInputs(Map<String, InputDefinition> inputs) {
        if (inputs == null) {
            return Map.of();
        }
        return inputs.entrySet().stream().collect(Collectors.toMap(
                Map.Entry::getKey,
                entry -> mapInput(entry.getValue())));
    }

    private static InputDefinitionDto mapInput(InputDefinition domain) {
        if (domain == null) {
            return null;
        }
        return new InputDefinitionDto(
                domain.name(),
                domain.type(),
                domain.required(),
                domain.defaultValue(),
                domain.description());
    }

    private static Map<String, OutputDefinitionDto> mapOutputs(Map<String, OutputDefinition> outputs) {
        if (outputs == null) {
            return Map.of();
        }
        return outputs.entrySet().stream().collect(Collectors.toMap(
                Map.Entry::getKey,
                entry -> mapOutput(entry.getValue())));
    }

    private static OutputDefinitionDto mapOutput(OutputDefinition domain) {
        if (domain == null) {
            return null;
        }
        return new OutputDefinitionDto(
                domain.name(),
                domain.type(),
                domain.description());
    }

    private static RetryPolicyDto mapRetryPolicy(RetryPolicy domain) {
        if (domain == null) {
            return null;
        }
        return new RetryPolicyDto(
                domain.maxAttempts(),
                domain.initialDelay().toSeconds(),
                domain.maxDelay().toSeconds(),
                domain.backoffMultiplier(),
                domain.retryableExceptions());
    }

    private static CompensationPolicyDto mapCompensationPolicy(CompensationPolicy domain) {
        if (domain == null) {
            return null;
        }
        return new CompensationPolicyDto(
                domain.strategy().name(),
                domain.timeout().toSeconds(),
                domain.failOnCompensationError());
    }
}
