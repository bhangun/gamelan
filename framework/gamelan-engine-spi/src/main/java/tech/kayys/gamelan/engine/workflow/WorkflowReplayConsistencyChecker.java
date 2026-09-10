package tech.kayys.gamelan.engine.workflow;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import tech.kayys.gamelan.engine.error.ErrorCode;
import tech.kayys.gamelan.engine.error.ErrorInfo;
import tech.kayys.gamelan.engine.error.GamelanException;
import tech.kayys.gamelan.engine.event.ExecutionEvent;
import tech.kayys.gamelan.engine.node.NodeExecution;
import tech.kayys.gamelan.engine.node.NodeId;
import tech.kayys.gamelan.engine.saga.CompensationState;

/**
 * Compares a persisted workflow snapshot with state rebuilt from its event stream.
 */
public final class WorkflowReplayConsistencyChecker {

    private WorkflowReplayConsistencyChecker() {
    }

    public static Report compare(
            WorkflowRunSnapshot snapshot,
            WorkflowDefinition definition,
            List<ExecutionEvent> events) {

        Objects.requireNonNull(snapshot, "snapshot cannot be null");
        Objects.requireNonNull(definition, "definition cannot be null");

        WorkflowRunSnapshot replayedSnapshot;
        try {
            WorkflowRun replayed = WorkflowRun.fromEvents(
                    snapshot.id(),
                    snapshot.tenantId(),
                    definition,
                    events != null ? events : List.of());
            replayedSnapshot = replayed.createSnapshot();
        } catch (RuntimeException error) {
            return new Report(snapshot.id(), Status.DRIFT, List.of(new Mismatch(
                    "replay",
                    "successful replay",
                    error.getClass().getSimpleName() + ": " + error.getMessage())));
        }

        List<Mismatch> mismatches = new ArrayList<>();
        compareValue(mismatches, "runId", snapshot.id(), replayedSnapshot.id());
        compareValue(mismatches, "tenantId", snapshot.tenantId(), replayedSnapshot.tenantId());
        compareValue(mismatches, "definitionId", snapshot.definitionId(), replayedSnapshot.definitionId());
        compareValue(mismatches, "definitionVersion", snapshot.definitionVersion(), replayedSnapshot.definitionVersion());
        compareValue(mismatches, "status", snapshot.status(), replayedSnapshot.status());
        compareValue(mismatches, "version", snapshot.version(), replayedSnapshot.version());
        compareValue(mismatches, "variables", snapshot.variables(), replayedSnapshot.variables());
        compareValue(mismatches, "executionPath", snapshot.executionPath(), replayedSnapshot.executionPath());
        compareNodeExecutions(mismatches, snapshot.nodeExecutions(), replayedSnapshot.nodeExecutions());
        compareCompensation(mismatches, snapshot.compensationState(), replayedSnapshot.compensationState());

        return new Report(snapshot.id(), mismatches);
    }

    private static void compareNodeExecutions(
            List<Mismatch> mismatches,
            Map<NodeId, NodeExecution> expected,
            Map<NodeId, NodeExecution> actual) {

        Map<NodeId, NodeExecution> expectedNodes = expected != null ? expected : Map.of();
        Map<NodeId, NodeExecution> actualNodes = actual != null ? actual : Map.of();
        Set<NodeId> nodeIds = new LinkedHashSet<>();
        nodeIds.addAll(expectedNodes.keySet());
        nodeIds.addAll(actualNodes.keySet());

        compareValue(mismatches, "nodeExecutions.ids", nodeIdValues(expectedNodes.keySet()),
                nodeIdValues(actualNodes.keySet()));
        for (NodeId nodeId : nodeIds) {
            NodeExecution expectedNode = expectedNodes.get(nodeId);
            NodeExecution actualNode = actualNodes.get(nodeId);
            String prefix = "nodeExecutions." + nodeId.value();

            if (expectedNode == null || actualNode == null) {
                compareValue(mismatches, prefix, nodeSummary(expectedNode), nodeSummary(actualNode));
                continue;
            }
            compareValue(mismatches, prefix + ".status", expectedNode.getStatus(), actualNode.getStatus());
            compareValue(mismatches, prefix + ".attempt", expectedNode.getAttempt(), actualNode.getAttempt());
            compareValue(mismatches, prefix + ".output", expectedNode.getOutput(), actualNode.getOutput());
            compareValue(mismatches, prefix + ".error", errorSummary(expectedNode.getLastError()),
                    errorSummary(actualNode.getLastError()));
        }
    }

    private static void compareCompensation(
            List<Mismatch> mismatches,
            CompensationState expected,
            CompensationState actual) {

        if (expected == null || actual == null) {
            compareValue(mismatches, "compensationState", compensationSummary(expected), compensationSummary(actual));
            return;
        }
        compareValue(mismatches, "compensationState.status", expected.status(), actual.status());
        compareValue(mismatches, "compensationState.nodesToCompensate", nodeIdValues(expected.nodesToCompensate()),
                nodeIdValues(actual.nodesToCompensate()));
        compareValue(mismatches, "compensationState.compensatedNodes", nodeIdValues(expected.compensatedNodes()),
                nodeIdValues(actual.compensatedNodes()));
    }

    private static void compareValue(List<Mismatch> mismatches, String field, Object expected, Object actual) {
        if (!Objects.equals(expected, actual)) {
            mismatches.add(new Mismatch(field, expected, actual));
        }
    }

    private static Map<String, Object> nodeSummary(NodeExecution node) {
        if (node == null) {
            return null;
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("status", node.getStatus());
        summary.put("attempt", node.getAttempt());
        summary.put("output", node.getOutput());
        summary.put("error", errorSummary(node.getLastError()));
        return summary;
    }

    private static Map<String, Object> errorSummary(ErrorInfo error) {
        if (error == null) {
            return null;
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("code", error.code());
        summary.put("message", error.message());
        summary.put("context", error.context());
        return summary;
    }

    private static Map<String, Object> compensationSummary(CompensationState state) {
        if (state == null) {
            return null;
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("status", state.status());
        summary.put("nodesToCompensate", nodeIdValues(state.nodesToCompensate()));
        summary.put("compensatedNodes", nodeIdValues(state.compensatedNodes()));
        return summary;
    }

    private static List<String> nodeIdValues(Iterable<NodeId> nodeIds) {
        if (nodeIds == null) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (NodeId nodeId : nodeIds) {
            if (nodeId != null) {
                values.add(nodeId.value());
            }
        }
        return List.copyOf(values);
    }

    public record Report(WorkflowRunId runId, Status status, List<Mismatch> mismatches) {
        public Report(WorkflowRunId runId, List<Mismatch> mismatches) {
            this(runId, inferStatus(mismatches), mismatches);
        }

        public Report {
            List<Mismatch> safeMismatches = mismatches != null ? List.copyOf(mismatches) : List.of();
            Status safeStatus = status != null ? status : inferStatus(safeMismatches);
            if (safeStatus == Status.CONSISTENT && !safeMismatches.isEmpty()) {
                safeStatus = Status.DRIFT;
            }
            status = safeStatus;
            mismatches = safeMismatches;
        }

        public boolean consistent() {
            return status == Status.CONSISTENT && mismatches.isEmpty();
        }

        public boolean drift() {
            return status == Status.DRIFT;
        }

        public boolean unavailable() {
            return status == Status.UNAVAILABLE;
        }

        public void throwIfInconsistent() {
            if (!consistent()) {
                throw new GamelanException(
                        ErrorCode.STORAGE_SERIALIZATION_FAILED,
                        "Workflow replay " + status.name().toLowerCase() + " for run " + safeRunId() + ": "
                                + mismatches.size() + " mismatch(es)");
            }
        }

        private String safeRunId() {
            return runId != null ? runId.value() : "<unknown>";
        }

        private static Status inferStatus(List<Mismatch> mismatches) {
            return mismatches == null || mismatches.isEmpty() ? Status.CONSISTENT : Status.DRIFT;
        }
    }

    public enum Status {
        CONSISTENT,
        DRIFT,
        UNAVAILABLE
    }

    public record Mismatch(String field, Object snapshotValue, Object replayedValue) {
    }
}
