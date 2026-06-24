package tech.kayys.gamelan.scheduler;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Objects;
import java.util.Optional;

import tech.kayys.gamelan.engine.node.NodeId;
import tech.kayys.gamelan.engine.tenant.TenantId;
import tech.kayys.gamelan.engine.workflow.WorkflowRunId;

public final class RetryEntries {

    private static final String SEPARATOR = ".";
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private RetryEntries() {
    }

    public static String encode(WorkflowRunId runId, NodeId nodeId) {
        return encode(runId, null, nodeId);
    }

    public static String encode(WorkflowRunId runId, TenantId tenantId, NodeId nodeId) {
        Objects.requireNonNull(runId, "WorkflowRunId cannot be null");
        Objects.requireNonNull(nodeId, "NodeId cannot be null");
        if (tenantId == null) {
            return encodePart(runId.value()) + SEPARATOR + encodePart(nodeId.value());
        }
        return encodePart(runId.value())
                + SEPARATOR
                + encodePart(tenantId.value())
                + SEPARATOR
                + encodePart(nodeId.value());
    }

    public static String encode(WorkflowRunId runId, TenantId tenantId, NodeId nodeId, int attempt) {
        Objects.requireNonNull(runId, "WorkflowRunId cannot be null");
        Objects.requireNonNull(nodeId, "NodeId cannot be null");
        validateAttempt(attempt);
        return encodePart(runId.value())
                + SEPARATOR
                + encodePart(tenantId != null ? tenantId.value() : "")
                + SEPARATOR
                + encodePart(nodeId.value())
                + SEPARATOR
                + encodePart(Integer.toString(attempt));
    }

    public static Optional<Entry> decode(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }

        String[] parts = value.split("\\" + SEPARATOR, -1);
        if (parts.length != 2 && parts.length != 3 && parts.length != 4) {
            return Optional.empty();
        }

        try {
            String runId = decodePart(parts[0]);
            TenantId tenantId = decodeTenant(parts);
            String nodeId = parts.length == 2 ? decodePart(parts[1]) : decodePart(parts[2]);
            Integer attempt = parts.length == 4 ? decodeAttempt(parts[3]) : null;
            return Optional.of(new Entry(WorkflowRunId.of(runId), tenantId, NodeId.of(nodeId), attempt));
        } catch (RuntimeException error) {
            return Optional.empty();
        }
    }

    private static TenantId decodeTenant(String[] parts) {
        if (parts.length < 3) {
            return null;
        }
        String tenant = decodePart(parts[1]);
        return tenant.isBlank() ? null : TenantId.of(tenant);
    }

    private static Integer decodeAttempt(String value) {
        int attempt = Integer.parseInt(decodePart(value));
        validateAttempt(attempt);
        return attempt;
    }

    private static void validateAttempt(int attempt) {
        if (attempt <= 0) {
            throw new IllegalArgumentException("Retry attempt must be positive");
        }
    }

    private static String encodePart(String value) {
        return ENCODER.encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String decodePart(String value) {
        return new String(DECODER.decode(value), StandardCharsets.UTF_8);
    }

    public record Entry(WorkflowRunId runId, TenantId tenantId, NodeId nodeId, Integer attempt) {
    }
}
