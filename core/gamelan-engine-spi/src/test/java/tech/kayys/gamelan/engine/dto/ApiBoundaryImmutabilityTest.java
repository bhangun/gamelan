package tech.kayys.gamelan.engine.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import tech.kayys.gamelan.engine.error.dto.ErrorDto;
import tech.kayys.gamelan.engine.execution.ExecutionMode;
import tech.kayys.gamelan.engine.execution.ExecutionRequest;
import tech.kayys.gamelan.engine.execution.ExecutionResponse;
import tech.kayys.gamelan.engine.execution.dto.ExecutionEventDto;
import tech.kayys.gamelan.engine.execution.dto.ExecutionHistoryResponse;
import tech.kayys.gamelan.engine.executor.dto.ExecutorRegistrationRequest;
import tech.kayys.gamelan.engine.io.dto.InputDefinitionDto;
import tech.kayys.gamelan.engine.io.dto.OutputDefinitionDto;
import tech.kayys.gamelan.engine.node.dto.NodeDefinitionDto;
import tech.kayys.gamelan.engine.node.dto.NodeExecutionDto;
import tech.kayys.gamelan.engine.node.dto.PagedResponse;
import tech.kayys.gamelan.engine.run.RunStatus;
import tech.kayys.gamelan.engine.run.dto.ResumeRunRequest;
import tech.kayys.gamelan.engine.run.dto.RetryPolicyDto;
import tech.kayys.gamelan.engine.signal.dto.ExternalSignalRequest;
import tech.kayys.gamelan.engine.signal.dto.SignalRequest;
import tech.kayys.gamelan.engine.tenant.TenantId;
import tech.kayys.gamelan.engine.transition.dto.TransitionDto;
import tech.kayys.gamelan.engine.workflow.WorkflowDefinitionId;
import tech.kayys.gamelan.engine.workflow.WorkflowRunId;
import tech.kayys.gamelan.engine.workflow.dto.CreateWorkflowDefinitionRequest;
import tech.kayys.gamelan.engine.workflow.dto.UpdateWorkflowDefinitionRequest;
import tech.kayys.gamelan.engine.workflow.dto.WorkflowDefinitionResponse;

class ApiBoundaryImmutabilityTest {

    @Test
    void signalRequestNormalizesBlankOptionalTarget() {
        SignalRequest request = new SignalRequest("ready", "  ", Map.of(), " key-1 ");

        assertEquals(null, request.targetNodeId());
        assertEquals("key-1", request.idempotencyKey());
    }

    @Test
    void requestPayloadDtosDefensivelyCopyAndFreezeNestedPayloads() {
        Map<String, Object> createInputs = nestedPayload("prompt", "draft");
        assertFrozenNestedPayload(
                new tech.kayys.gamelan.engine.run.dto.CreateRunRequest(
                        "workflow-1",
                        createInputs,
                        null,
                        null).inputs(),
                createInputs,
                "prompt",
                "draft");

        Map<String, Object> resumeData = nestedPayload("decision", "approve");
        assertFrozenNestedPayload(
                new ResumeRunRequest(resumeData, "task-1").resumeData(),
                resumeData,
                "decision",
                "approve");

        Map<String, Object> signalPayload = nestedPayload("signal", "ready");
        assertFrozenNestedPayload(
                new SignalRequest("ready", "node-1", signalPayload, null).payload(),
                signalPayload,
                "signal",
                "ready");

        Map<String, Object> externalPayload = nestedPayload("callback", "complete");
        assertFrozenNestedPayload(
                new ExternalSignalRequest(
                        "callback",
                        "node-1",
                        "executor",
                        externalPayload,
                        Instant.EPOCH,
                        "sig").payload(),
                externalPayload,
                "callback",
                "complete");

        Map<String, Object> executionInput = nestedPayload("input", 7);
        assertFrozenNestedPayload(
                new ExecutionRequest(
                        WorkflowDefinitionId.of("workflow-1"),
                        TenantId.of("tenant-1"),
                        executionInput,
                        ExecutionMode.ASYNC,
                        "corr-1").input(),
                executionInput,
                "input",
                7);

        Map<String, Object> runInputs = nestedPayload("run", "start");
        assertFrozenNestedPayload(
                new tech.kayys.gamelan.engine.run.CreateRunRequest(
                        "workflow-1",
                        "1.0.0",
                        runInputs,
                        "corr-1",
                        true).getInputs(),
                runInputs,
                "run",
                "start");
    }

    @Test
    void responsePayloadDtosDefensivelyCopyAndFreezeNestedPayloads() {
        Map<String, Object> output = nestedPayload("answer", 42);
        assertFrozenNestedPayload(
                new NodeExecutionDto("node-1", "Node", "COMPLETED", 1, null, null, null, output, null).output(),
                output,
                "answer",
                42);

        Map<String, Object> errorContext = nestedPayload("reason", "timeout");
        assertFrozenNestedPayload(
                new ErrorDto("TIMEOUT", "Timed out", null, errorContext).context(),
                errorContext,
                "reason",
                "timeout");

        Map<String, Object> eventData = nestedPayload("event", "node-completed");
        assertFrozenNestedPayload(
                new ExecutionEventDto("event-1", "NodeCompleted", 1, Instant.EPOCH, eventData).eventData(),
                eventData,
                "event",
                "node-completed");

        Map<String, Object> variables = nestedPayload("state", "running");
        assertFrozenNestedPayload(
                new tech.kayys.gamelan.engine.run.dto.RunResponse(
                        "run-1",
                        "tenant-1",
                        "workflow-1",
                        "1.0.0",
                        "RUNNING",
                        variables,
                        null,
                        null,
                        Instant.EPOCH,
                        null,
                        null,
                        null,
                        null,
                        null).variables(),
                variables,
                "state",
                "running");

        Map<String, Object> outputs = nestedPayload("result", "ok");
        assertFrozenNestedPayload(
                new tech.kayys.gamelan.engine.run.RunResponse(
                        "run-1",
                        "workflow-1",
                        "1.0.0",
                        "COMPLETED",
                        "DONE",
                        Instant.EPOCH,
                        null,
                        null,
                        null,
                        1,
                        1,
                        1,
                        3,
                        null,
                        outputs).getOutputs(),
                outputs,
                "result",
                "ok");

        Map<String, Object> defaultValue = nestedPayload("template", "agent.md");
        assertFrozenNestedPayload(
                asPayload(new InputDefinitionDto("prompt", "object", false, defaultValue, "Prompt").defaultValue()),
                defaultValue,
                "template",
                "agent.md");

        Map<String, Object> responseOutput = nestedPayload("summary", "done");
        assertFrozenNestedPayload(
                asPayload(new ExecutionResponse(
                        WorkflowRunId.of("run-1"),
                        RunStatus.COMPLETED,
                        responseOutput,
                        "ok").output()),
                responseOutput,
                "summary",
                "done");
    }

    @Test
    void structuralDtoContainersAreDefensivelyCopiedAndFrozen() {
        Map<String, String> metadata = stringMap("domain", "agentic-ai");
        ExecutorRegistrationRequest registration = new ExecutorRegistrationRequest(
                "executor-1",
                "agent",
                "grpc",
                "localhost:9000",
                metadata);
        assertFrozenStringMap(registration.metadata(), metadata);

        List<String> retryable = new ArrayList<>(List.of("IOException"));
        RetryPolicyDto retryPolicy = new RetryPolicyDto(3, 1, 30, 2.0, retryable);
        assertFrozenList(retryPolicy.retryableExceptions(), retryable, "IOException");

        List<String> pageContent = new ArrayList<>(List.of("run-1"));
        PagedResponse<String> page = new PagedResponse<>(pageContent, 0, 10, 1, false);
        assertFrozenList(page.content(), pageContent, "run-1");

        List<ExecutionEventDto> events = new ArrayList<>(List.of(
                new ExecutionEventDto("event-1", "Started", 1, Instant.EPOCH, null)));
        ExecutionHistoryResponse history = new ExecutionHistoryResponse("run-1", events, 1);
        assertFrozenList(history.events(), events, events.get(0));

        Map<String, Object> configuration = nestedPayload("model", "local-coder");
        List<String> dependsOn = new ArrayList<>(List.of("node-a"));
        List<TransitionDto> transitions = new ArrayList<>(List.of(new TransitionDto("node-b", null, "SUCCESS")));
        NodeDefinitionDto node = new NodeDefinitionDto(
                "node-1",
                "Agent",
                "AGENT_LOOP",
                "local",
                configuration,
                dependsOn,
                transitions,
                retryPolicy,
                30L,
                true);
        assertFrozenNestedPayload(node.configuration(), configuration, "model", "local-coder");
        assertFrozenList(node.dependsOn(), dependsOn, "node-a");
        assertFrozenList(node.transitions(), transitions, transitions.get(0));

        assertWorkflowDefinitionContainersAreFrozen(node);
    }

    private static void assertWorkflowDefinitionContainersAreFrozen(NodeDefinitionDto node) {
        List<NodeDefinitionDto> nodes = new ArrayList<>(List.of(node));
        Map<String, InputDefinitionDto> inputs = new LinkedHashMap<>();
        inputs.put("prompt", new InputDefinitionDto("prompt", "string", true, null, "Prompt"));
        Map<String, OutputDefinitionDto> outputs = new LinkedHashMap<>();
        outputs.put("answer", new OutputDefinitionDto("answer", "string", "Answer"));
        Map<String, String> metadata = stringMap("team", "automation");

        CreateWorkflowDefinitionRequest createRequest = new CreateWorkflowDefinitionRequest(
                "workflow",
                "1.0.0",
                "desc",
                nodes,
                inputs,
                outputs,
                null,
                null,
                metadata);
        assertFrozenList(createRequest.nodes(), nodes, node);
        assertFrozenMap(createRequest.inputs(), inputs);
        assertFrozenMap(createRequest.outputs(), outputs);
        assertFrozenStringMap(createRequest.metadata(), metadata);

        Map<String, String> updateMetadata = stringMap("owner", "ops");
        UpdateWorkflowDefinitionRequest updateRequest = new UpdateWorkflowDefinitionRequest(
                "desc",
                true,
                updateMetadata);
        assertFrozenStringMap(updateRequest.metadata(), updateMetadata);

        List<NodeDefinitionDto> responseNodes = new ArrayList<>(List.of(node));
        Map<String, String> responseMetadata = stringMap("profile", "business-automation");
        WorkflowDefinitionResponse response = new WorkflowDefinitionResponse(
                "workflow-1",
                "workflow",
                "1.0.0",
                "desc",
                responseNodes,
                inputs,
                outputs,
                true,
                Instant.EPOCH,
                responseMetadata);
        assertFrozenList(response.nodes(), responseNodes, node);
        assertFrozenMap(response.inputs(), inputs);
        assertFrozenMap(response.outputs(), outputs);
        assertFrozenStringMap(response.metadata(), responseMetadata);
    }

    private static Map<String, String> stringMap(String key, String value) {
        Map<String, String> map = new LinkedHashMap<>();
        map.put(key, value);
        return map;
    }

    private static Map<String, Object> nestedPayload(String key, Object value) {
        Map<String, Object> nested = new HashMap<>();
        nested.put(key, value);
        Map<String, Object> payload = new HashMap<>();
        payload.put("nested", nested);
        return payload;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asPayload(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static void assertFrozenNestedPayload(
            Map<String, Object> storedPayload,
            Map<String, Object> callerPayload,
            String key,
            Object expectedValue) {

        Map<String, Object> callerNested = (Map<String, Object>) callerPayload.get("nested");
        callerPayload.put("late", "ignored");
        callerNested.put(key, "mutated");

        Map<String, Object> storedNested = (Map<String, Object>) storedPayload.get("nested");
        assertEquals(expectedValue, storedNested.get(key));
        assertFalse(storedPayload.containsKey("late"));
        assertThrows(UnsupportedOperationException.class, () -> storedPayload.put("x", "y"));
        assertThrows(UnsupportedOperationException.class, () -> storedNested.put("x", "y"));
    }

    private static <T> void assertFrozenList(List<T> stored, List<T> caller, T expectedFirst) {
        caller.add(expectedFirst);

        assertEquals(List.of(expectedFirst), stored);
        assertThrows(UnsupportedOperationException.class, () -> stored.add(expectedFirst));
    }

    private static <K, V> void assertFrozenMap(Map<K, V> stored, Map<K, V> caller) {
        Map<K, V> expected = new LinkedHashMap<>(caller);

        caller.clear();

        assertEquals(expected, stored);
        assertThrows(UnsupportedOperationException.class, stored::clear);
    }

    private static void assertFrozenStringMap(Map<String, String> stored, Map<String, String> caller) {
        Map<String, String> expected = new LinkedHashMap<>(caller);

        caller.put("late", "ignored");

        assertEquals(expected, stored);
        assertThrows(UnsupportedOperationException.class, () -> stored.put("x", "y"));
    }
}
