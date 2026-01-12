package tech.kayys.silat.dispatcher;

import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.core.buffer.Buffer;
import io.vertx.mutiny.ext.web.client.WebClient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import tech.kayys.silat.execution.NodeExecutionTask;
import tech.kayys.silat.model.ExecutionToken;
import tech.kayys.silat.model.ExecutorInfo;

@ApplicationScoped
public class RestTaskDispatcher {

    private static final Logger LOG = LoggerFactory.getLogger(RestTaskDispatcher.class);

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);

    @Inject
    WebClient webClient;

    @Inject
    ObjectMapper objectMapper;

    public Uni<Void> dispatch(NodeExecutionTask task, ExecutorInfo executor) {

        Objects.requireNonNull(task, "NodeExecutionTask cannot be null");
        Objects.requireNonNull(executor, "ExecutorInfo cannot be null");

        String endpoint = executor.endpoint();
        if (endpoint == null || endpoint.isBlank()) {
            return Uni.createFrom().failure(
                    new IllegalArgumentException("Executor REST endpoint is missing"));
        }

        RestExecutionRequest payload = RestExecutionRequest.from(task, executor);

        return Uni.createFrom().item(payload)
                .onItem().transformToUni(req -> sendRequest(req, executor))
                .onFailure().invoke(t -> LOG.error("REST dispatch failed for run={}, node={}, executor={}",
                        task.runId().value(),
                        task.nodeId().value(),
                        executor.executorId(),
                        t))
                .replaceWithVoid();
    }

    private Uni<Void> sendRequest(RestExecutionRequest request, ExecutorInfo executor) {

        String body;
        try {
            body = objectMapper.writeValueAsString(request);
        } catch (Exception e) {
            return Uni.createFrom().failure(
                    new IllegalStateException("Failed to serialize execution request", e));
        }

        return webClient
                .postAbs(executor.endpoint())
                .putHeader("Content-Type", "application/json")
                .putHeader("X-Executor-Id", executor.executorId())
                .putHeader("X-Run-Id", request.runId())
                .putHeader("X-Node-Id", request.nodeId())
                .putHeader("X-Attempt", String.valueOf(request.attempt()))
                .putHeader("X-Idempotency-Key", request.idempotencyKey())
                .putHeader("X-Signature", request.signature())
                .timeout(resolveTimeout(executor).toMillis())
                .sendBuffer(Buffer.buffer(body))
                .onItem().transformToUni(resp -> {
                    int status = resp.statusCode();

                    if (status >= 200 && status < 300) {
                        LOG.debug("REST task accepted: run={}, node={}, executor={}",
                                request.runId(),
                                request.nodeId(),
                                executor.executorId());
                        return Uni.createFrom().voidItem();
                    }

                    return Uni.createFrom().failure(
                            new TaskDispatchException(
                                    "Executor rejected task, status=" + status,
                                    status,
                                    resp.bodyAsString()));
                });
    }

    private Duration resolveTimeout(ExecutorInfo executor) {
        return executor.timeout() != null
                ? executor.timeout()
                : DEFAULT_TIMEOUT;
    }

    /* ===================== INTERNAL DTO ===================== */

    static final class RestExecutionRequest {

        private final String runId;
        private final String nodeId;
        private final int attempt;
        private final ExecutionToken token;
        private final Map<String, Object> variables;
        private final String idempotencyKey;
        private final String signature;

        private RestExecutionRequest(
                String runId,
                String nodeId,
                int attempt,
                ExecutionToken token,
                Map<String, Object> variables,
                String idempotencyKey,
                String signature) {

            this.runId = runId;
            this.nodeId = nodeId;
            this.attempt = attempt;
            this.token = token;
            this.variables = variables;
            this.idempotencyKey = idempotencyKey;
            this.signature = signature;
        }

        static RestExecutionRequest from(NodeExecutionTask task, ExecutorInfo executor) {

            String idempotencyKey = task.runId().value()
                    + ":" + task.nodeId().value()
                    + ":" + task.attempt();

            return new RestExecutionRequest(
                    task.runId().value(),
                    task.nodeId().value(),
                    task.attempt(),
                    task.token(),
                    task.context(),
                    idempotencyKey,
                    sign(task, executor));
        }

        static String sign(NodeExecutionTask task, ExecutorInfo executor) {
            // placeholder — replace with HMAC / asymmetric signing
            return Base64.getEncoder()
                    .encodeToString((task.runId().value()
                            + executor.executorId()).getBytes());
        }

        public ExecutionToken token() {
            return token;
        }

        public Map<String, Object> variables() {
            return variables;
        }

        public String runId() {
            return runId;
        }

        public String nodeId() {
            return nodeId;
        }

        public int attempt() {
            return attempt;
        }

        public String idempotencyKey() {
            return idempotencyKey;
        }

        public String signature() {
            return signature;
        }
    }
}
