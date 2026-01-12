package tech.kayys.silat.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public class WaitInfo {
    public enum WaitType {
        HUMAN_APPROVAL, EXTERNAL_CALLBACK, TIMER, CONDITION, RESOURCE, 
        DEPENDENCY, MANUAL_INTERVENTION, RATE_LIMIT, CUSTOM
    }

    private final String waitId;
    private final WaitType waitType;
    private final String nodeId;
    private final String reason;
    private final Instant waitStartedAt;
    private final Duration timeout;
    private final Map<String, Object> waitData;
    private final Map<String, Object> metadata;

    @JsonCreator
    public WaitInfo(
            @JsonProperty("waitId") String waitId,
            @JsonProperty("waitType") WaitType waitType,
            @JsonProperty("nodeId") String nodeId,
            @JsonProperty("reason") String reason,
            @JsonProperty("waitStartedAt") Instant waitStartedAt,
            @JsonProperty("timeout") Duration timeout,
            @JsonProperty("waitData") Map<String, Object> waitData,
            @JsonProperty("metadata") Map<String, Object> metadata) {
        this.waitId = waitId != null ? waitId : UUID.randomUUID().toString();
        this.waitType = waitType;
        this.nodeId = nodeId;
        this.reason = reason;
        this.waitStartedAt = waitStartedAt != null ? waitStartedAt : Instant.now();
        this.timeout = timeout;
        this.waitData = waitData != null ? Map.copyOf(waitData) : Map.of();
        this.metadata = metadata != null ? Map.copyOf(metadata) : Map.of();
    }

    public String getWaitId() { return waitId; }
    public WaitType getWaitType() { return waitType; }
    public String getNodeId() { return nodeId; }
    public String getReason() { return reason; }
    public Instant getWaitStartedAt() { return waitStartedAt; }
    public Duration getTimeout() { return timeout; }
    public Map<String, Object> getWaitData() { return waitData; }
    public Map<String, Object> getMetadata() { return metadata; }

    public static Builder builder() { return new Builder(); }
    public Builder toBuilder() {
        return builder().waitId(waitId).waitType(waitType).nodeId(nodeId).reason(reason)
                .waitStartedAt(waitStartedAt).timeout(timeout).waitData(waitData).metadata(metadata);
    }

    public static WaitInfo humanApproval(String nodeId, String approvalRequestId, String approver) {
        return builder().waitType(WaitType.HUMAN_APPROVAL).nodeId(nodeId).reason("Waiting for human approval")
                .timeout(Duration.ofDays(7))
                .waitData(Map.of("approvalRequestId", approvalRequestId, "approver", approver, "requiredAction", "approve_or_reject"))
                .metadata(Map.of("requiresHuman", true, "interactive", true)).build();
    }

    public static WaitInfo externalCallback(String nodeId, String callbackUrl, String expectedResponse) {
        return builder().waitType(WaitType.EXTERNAL_CALLBACK).nodeId(nodeId).reason("Waiting for external service callback")
                .timeout(Duration.ofHours(1))
                .waitData(Map.of("callbackUrl", callbackUrl, "expectedResponse", expectedResponse, "retryAttempt", 0))
                .metadata(Map.of("externalService", true, "async", true)).build();
    }

    public static WaitInfo timer(String nodeId, Duration duration, String timerName) {
        return builder().waitType(WaitType.TIMER).nodeId(nodeId).reason("Waiting for timer: " + timerName)
                .timeout(duration.plus(Duration.ofMinutes(5)))
                .waitData(Map.of("duration", duration.toMillis(), "timerName", timerName, "expiresAt", Instant.now().plus(duration).toString()))
                .metadata(Map.of("scheduled", true, "autoResume", true)).build();
    }

    public static WaitInfo condition(String nodeId, String conditionExpression) {
        return builder().waitType(WaitType.CONDITION).nodeId(nodeId).reason("Waiting for condition: " + conditionExpression)
                .timeout(Duration.ofHours(24))
                .waitData(Map.of("condition", conditionExpression, "pollInterval", Duration.ofSeconds(30).toMillis()))
                .metadata(Map.of("conditional", true, "pollingRequired", true)).build();
    }

    public boolean isExpired() {
        if (timeout == null) return false;
        return Instant.now().isAfter(waitStartedAt.plus(timeout));
    }

    public Duration getRemainingTime() {
        if (timeout == null) return Duration.ZERO;
        Instant expiresAt = waitStartedAt.plus(timeout);
        return Duration.between(Instant.now(), expiresAt);
    }

    public boolean isHumanInvolved() { return waitType == WaitType.HUMAN_APPROVAL || waitType == WaitType.MANUAL_INTERVENTION; }
    public boolean isExternalService() { return waitType == WaitType.EXTERNAL_CALLBACK; }
    public boolean isTimer() { return waitType == WaitType.TIMER; }

    public static class Builder {
        private String waitId;
        private WaitType waitType;
        private String nodeId;
        private String reason;
        private Instant waitStartedAt;
        private Duration timeout;
        private Map<String, Object> waitData;
        private Map<String, Object> metadata;

        public Builder waitId(String waitId) { this.waitId = waitId; return this; }
        public Builder waitType(WaitType waitType) { this.waitType = waitType; return this; }
        public Builder nodeId(String nodeId) { this.nodeId = nodeId; return this; }
        public Builder reason(String reason) { this.reason = reason; return this; }
        public Builder waitStartedAt(Instant waitStartedAt) { this.waitStartedAt = waitStartedAt; return this; }
        public Builder timeout(Duration timeout) { this.timeout = timeout; return this; }
        public Builder waitData(Map<String, Object> waitData) { this.waitData = waitData; return this; }
        public Builder metadata(Map<String, Object> metadata) { this.metadata = metadata; return this; }

        public WaitInfo build() {
            return new WaitInfo(waitId, waitType, nodeId, reason, waitStartedAt, timeout, waitData, metadata);
        }
    }
}
