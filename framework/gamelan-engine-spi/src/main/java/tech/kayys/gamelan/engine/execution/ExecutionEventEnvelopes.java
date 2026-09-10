package tech.kayys.gamelan.engine.execution;

import java.util.List;
import java.util.Map;

import tech.kayys.gamelan.engine.event.CompensationCompletedEvent;
import tech.kayys.gamelan.engine.event.CompensationFailedEvent;
import tech.kayys.gamelan.engine.event.CompensationStartedEvent;
import tech.kayys.gamelan.engine.event.ExecutionEvent;
import tech.kayys.gamelan.engine.event.GenericExecutionEvent;
import tech.kayys.gamelan.engine.event.WorkflowStartedEvent;
import tech.kayys.gamelan.engine.node.NodeId;
import tech.kayys.gamelan.engine.tenant.TenantId;
import tech.kayys.gamelan.engine.workflow.WorkflowDefinitionId;
import tech.kayys.gamelan.engine.workflow.WorkflowId;
import tech.kayys.gamelan.engine.workflow.WorkflowRunId;

/**
 * Guardrails for execution event streams before they are persisted or replayed.
 */
public final class ExecutionEventEnvelopes {

    private ExecutionEventEnvelopes() {
    }

    public static List<ExecutionEvent> validateForRun(
            WorkflowRunId runId,
            List<ExecutionEvent> events) {
        return validateForRun(runId, null, events);
    }

    public static List<ExecutionEvent> validateForRun(
            WorkflowRunId runId,
            TenantId expectedTenantId,
            List<ExecutionEvent> events) {
        if (runId == null) {
            throw new IllegalArgumentException("WorkflowRunId cannot be null");
        }
        if (events == null || events.isEmpty()) {
            return List.of();
        }

        String tenantId = stringValue(expectedTenantId);
        String workflowId = null;
        String workflowVersion = null;
        for (ExecutionEvent event : events) {
            validateRunId(runId, event);
            tenantId = ensureConsistentEnvelopeValue(
                    "tenant id",
                    tenantId,
                    tenantIdFromEvent(event));
            workflowId = ensureConsistentEnvelopeValue(
                    "workflow id",
                    workflowId,
                    workflowIdFromEvent(event));
            workflowVersion = ensureConsistentEnvelopeValue(
                    "workflow version",
                    workflowVersion,
                    workflowVersionFromEvent(event));
        }
        return List.copyOf(events);
    }

    public static String safeEventType(ExecutionEvent event) {
        return event.eventType() != null && !event.eventType().isBlank() ? event.eventType() : "Unknown";
    }

    private static void validateRunId(WorkflowRunId runId, ExecutionEvent event) {
        if (event == null) {
            throw new IllegalArgumentException("Execution history contains null event");
        }
        if (event.runId() == null) {
            throw new IllegalArgumentException(
                    "Execution history event has no run id: " + safeEventType(event));
        }
        if (!runId.equals(event.runId())) {
            throw new IllegalArgumentException(
                    "Execution history event run id mismatch: expected "
                            + runId.value()
                            + " but found "
                            + event.runId().value());
        }
    }

    private static String ensureConsistentEnvelopeValue(
            String label,
            String current,
            String candidate) {
        if (candidate == null) {
            return current;
        }
        if (current == null) {
            return candidate;
        }
        if (!current.equals(candidate)) {
            throw new IllegalArgumentException(
                    "Execution history event "
                            + label
                            + " mismatch: expected "
                            + current
                            + " but found "
                            + candidate);
        }
        return current;
    }

    private static String workflowIdFromEvent(ExecutionEvent event) {
        if (event instanceof WorkflowStartedEvent started) {
            return stringValue(started.definitionId());
        }
        if (event instanceof GenericExecutionEvent generic) {
            return workflowIdFromMetadata(generic.metadata());
        }
        return null;
    }

    private static String workflowVersionFromEvent(ExecutionEvent event) {
        if (event instanceof WorkflowStartedEvent started) {
            return stringValue(started.workflowVersion());
        }
        if (event instanceof GenericExecutionEvent generic) {
            return workflowVersionFromMetadata(generic.metadata());
        }
        return null;
    }

    private static String workflowIdFromMetadata(Map<String, Object> metadata) {
        return firstPresent(
                metadataValue(metadata, "workflowId"),
                metadataValue(metadata, "definitionId"),
                metadataValue(metadata, "workflowDefinitionId"));
    }

    private static String workflowVersionFromMetadata(Map<String, Object> metadata) {
        return firstPresent(
                metadataValue(metadata, "workflowVersion"),
                metadataValue(metadata, "definitionVersion"));
    }

    private static String tenantIdFromEvent(ExecutionEvent event) {
        if (event instanceof WorkflowStartedEvent started) {
            return stringValue(started.tenantId());
        }
        if (event instanceof CompensationStartedEvent started) {
            return stringValue(started.tenantId());
        }
        if (event instanceof CompensationCompletedEvent completed) {
            return stringValue(completed.tenantId());
        }
        if (event instanceof CompensationFailedEvent failed) {
            return stringValue(failed.tenantId());
        }
        if (event instanceof GenericExecutionEvent generic) {
            return tenantIdFromMetadata(generic.metadata());
        }
        return null;
    }

    private static String tenantIdFromMetadata(Map<String, Object> metadata) {
        if (metadata == null) {
            return null;
        }
        return metadataValue(metadata, "tenantId");
    }

    private static String metadataValue(Map<String, Object> metadata, String key) {
        if (metadata == null) {
            return null;
        }
        return stringValue(metadata.get(key));
    }

    private static String firstPresent(String... candidates) {
        for (String candidate : candidates) {
            if (candidate != null) {
                return candidate;
            }
        }
        return null;
    }

    private static String stringValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof WorkflowDefinitionId id) {
            return id.value();
        }
        if (value instanceof WorkflowId id) {
            return id.getId();
        }
        if (value instanceof WorkflowRunId id) {
            return id.value();
        }
        if (value instanceof TenantId id) {
            return id.value();
        }
        if (value instanceof NodeId id) {
            return id.value();
        }
        if (value instanceof Map<?, ?> map) {
            String byValue = stringValue(map.get("value"));
            return byValue != null ? byValue : stringValue(map.get("id"));
        }
        String text = String.valueOf(value);
        return text.isBlank() ? null : text;
    }
}
