package tech.kayys.gamelan.engine.signal;

import java.time.Instant;
import java.util.Map;

import tech.kayys.gamelan.engine.workflow.WorkflowRunId;

public record SignalContext(
                WorkflowRunId runId,
                String signalType,
                Map<String, Object> payload,
                Instant receivedAt) {
}
