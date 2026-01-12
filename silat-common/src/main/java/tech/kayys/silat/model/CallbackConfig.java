package tech.kayys.silat.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/**
 * Configuration for external callbacks.
 * Used when workflow needs to wait for external signals.
 */
public class CallbackConfig {
    private final Duration timeout;
    private final int maxRetries;
    private final Duration retryDelay;
    private final String callbackUrl;
    private final Map<String, String> headers;
    private final Map<String, Object> expectedPayload;
    private final String validationSchema;
    private final CallbackMethod method;
    private final String contentType;
    private final boolean awaitResponse;
    private final Map<String, Object> metadata;

    @JsonCreator
    public CallbackConfig(
            @JsonProperty("timeout") Duration timeout,
            @JsonProperty("maxRetries") int maxRetries,
            @JsonProperty("retryDelay") Duration retryDelay,
            @JsonProperty("callbackUrl") String callbackUrl,
            @JsonProperty("headers") Map<String, String> headers,
            @JsonProperty("expectedPayload") Map<String, Object> expectedPayload,
            @JsonProperty("validationSchema") String validationSchema,
            @JsonProperty("method") CallbackMethod method,
            @JsonProperty("contentType") String contentType,
            @JsonProperty("awaitResponse") boolean awaitResponse,
            @JsonProperty("metadata") Map<String, Object> metadata) {
        this.timeout = timeout != null ? timeout : Duration.ofHours(24);
        this.maxRetries = maxRetries;
        this.retryDelay = retryDelay != null ? retryDelay : Duration.ofMinutes(5);
        this.callbackUrl = callbackUrl;
        this.headers = headers != null ? Map.copyOf(headers) : Map.of();
        this.expectedPayload = expectedPayload != null ? Map.copyOf(expectedPayload) : Map.of();
        this.validationSchema = validationSchema;
        this.method = method != null ? method : CallbackMethod.POST;
        this.contentType = contentType != null ? contentType : "application/json";
        this.awaitResponse = awaitResponse;
        this.metadata = metadata != null ? Map.copyOf(metadata) : Map.of();
    }

    public enum CallbackMethod { GET, POST, PUT, PATCH }

    public Duration getTimeout() { return timeout; }
    public int getMaxRetries() { return maxRetries; }
    public Duration getRetryDelay() { return retryDelay; }
    public String getCallbackUrl() { return callbackUrl; }
    public Map<String, String> getHeaders() { return headers; }
    public Map<String, Object> getExpectedPayload() { return expectedPayload; }
    public String getValidationSchema() { return validationSchema; }
    public CallbackMethod getMethod() { return method; }
    public String getContentType() { return contentType; }
    public boolean isAwaitResponse() { return awaitResponse; }
    public Map<String, Object> getMetadata() { return metadata; }

    public static Builder builder() { return new Builder(); }
    public Builder toBuilder() {
        return builder()
                .timeout(timeout).maxRetries(maxRetries).retryDelay(retryDelay)
                .callbackUrl(callbackUrl).headers(headers).expectedPayload(expectedPayload)
                .validationSchema(validationSchema).method(method).contentType(contentType)
                .awaitResponse(awaitResponse).metadata(metadata);
    }

    public static CallbackConfig webhook(String url) {
        return builder()
                .callbackUrl(url)
                .timeout(Duration.ofMinutes(30))
                .headers(Map.of("Content-Type", "application/json", "User-Agent", "WorkflowEngine/1.0"))
                .build();
    }

    public static CallbackConfig humanApproval(String approvalUrl) {
        return builder()
                .callbackUrl(approvalUrl)
                .timeout(Duration.ofDays(7))
                .expectedPayload(Map.of("action", "approve|reject", "approver", String.class, "comment", String.class))
                .headers(Map.of("Content-Type", "application/json", "X-Approval-Required", "true"))
                .metadata(Map.of("type", "human_approval"))
                .build();
    }

    public static CallbackConfig externalService(String serviceUrl, Map<String, String> authHeaders) {
        java.util.Map<String, String> headers = new java.util.HashMap<>();
        headers.put("Content-Type", "application/json");
        if (authHeaders != null) headers.putAll(authHeaders);
        return builder()
                .callbackUrl(serviceUrl).timeout(Duration.ofMinutes(10))
                .headers(headers).maxRetries(5).retryDelay(Duration.ofSeconds(30))
                .metadata(Map.of("type", "external_service"))
                .build();
    }

    public boolean requiresAuthentication() {
        return headers.containsKey("Authorization") || headers.containsKey("X-API-Key");
    }

    public boolean hasExpectedPayload() {
        return expectedPayload != null && !expectedPayload.isEmpty();
    }

    public static class Builder {
        private Duration timeout;
        private int maxRetries;
        private Duration retryDelay;
        private String callbackUrl;
        private Map<String, String> headers;
        private Map<String, Object> expectedPayload;
        private String validationSchema;
        private CallbackMethod method;
        private String contentType;
        private boolean awaitResponse;
        private Map<String, Object> metadata;

        public Builder timeout(Duration timeout) { this.timeout = timeout; return this; }
        public Builder maxRetries(int maxRetries) { this.maxRetries = maxRetries; return this; }
        public Builder retryDelay(Duration retryDelay) { this.retryDelay = retryDelay; return this; }
        public Builder callbackUrl(String callbackUrl) { this.callbackUrl = callbackUrl; return this; }
        public Builder headers(Map<String, String> headers) { this.headers = headers; return this; }
        public Builder expectedPayload(Map<String, Object> expectedPayload) { this.expectedPayload = expectedPayload; return this; }
        public Builder validationSchema(String validationSchema) { this.validationSchema = validationSchema; return this; }
        public Builder method(CallbackMethod method) { this.method = method; return this; }
        public Builder contentType(String contentType) { this.contentType = contentType; return this; }
        public Builder awaitResponse(boolean awaitResponse) { this.awaitResponse = awaitResponse; return this; }
        public Builder metadata(Map<String, Object> metadata) { this.metadata = metadata; return this; }

        public CallbackConfig build() {
            return new CallbackConfig(timeout, maxRetries, retryDelay, callbackUrl, headers, expectedPayload, 
                                      validationSchema, method, contentType, awaitResponse, metadata);
        }
    }
}
