package tech.kayys.gamelan.engine.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import io.smallrye.mutiny.Uni;
import tech.kayys.gamelan.engine.callback.CallbackRegistration;
import tech.kayys.gamelan.engine.execution.ExecutionToken;
import tech.kayys.gamelan.engine.execution.ExecutionTokenHash;
import tech.kayys.gamelan.engine.node.DefaultNodeExecutionResult;
import tech.kayys.gamelan.engine.node.NodeExecutionResult;
import tech.kayys.gamelan.engine.node.NodeExecutionSnapshot;
import tech.kayys.gamelan.engine.node.NodeExecutionStatus;
import tech.kayys.gamelan.engine.node.NodeId;
import tech.kayys.gamelan.engine.repository.WorkflowRunRepository;
import tech.kayys.gamelan.engine.run.RunStatus;
import tech.kayys.gamelan.engine.tenant.TenantId;
import tech.kayys.gamelan.engine.workflow.WorkflowDefinitionId;
import tech.kayys.gamelan.engine.workflow.WorkflowRun;
import tech.kayys.gamelan.engine.workflow.WorkflowRunId;
import tech.kayys.gamelan.engine.workflow.WorkflowRunSnapshot;

class DefaultExecutionTokenServiceTest {

    private static final WorkflowRunId RUN_ID = WorkflowRunId.of("run-1");
    private static final TenantId TENANT_ID = TenantId.of("tenant-1");
    private static final NodeId NODE_ID = NodeId.of("node-1");

    @Test
    void issueStoresSecureTokenWithDefaultTtl() {
        TokenRepository repository = new TokenRepository();
        DefaultExecutionTokenService service = service(repository);
        Instant before = Instant.now();

        ExecutionToken token = service.issue(RUN_ID, NODE_ID, 1).await().indefinitely();

        assertEquals(RUN_ID, token.runId());
        assertEquals(NODE_ID, token.nodeId());
        assertEquals(1, token.attempt());
        assertTrue(token.token().matches("[A-Za-z0-9_-]{43}"));
        assertTrue(token.expiresAt().isAfter(before.plus(Duration.ofMinutes(4))));
        assertTrue(token.expiresAt().isBefore(Instant.now().plus(Duration.ofMinutes(6))));
        assertEquals(1, repository.tokens.size());
        assertTrue(repository.validateToken(token).await().indefinitely());
    }

    @Test
    void issueStoresTenantBoundToken() {
        TokenRepository repository = new TokenRepository();
        DefaultExecutionTokenService service = service(repository);

        ExecutionToken token = service.issue(RUN_ID, TENANT_ID, NODE_ID, 1).await().indefinitely();
        ExecutionToken crossTenantReplay = new ExecutionToken(
                token.value(),
                RUN_ID,
                TenantId.of("tenant-2"),
                NODE_ID,
                1,
                token.expiresAt());

        assertEquals(TENANT_ID, token.tenantId());
        assertTrue(repository.validateToken(token).await().indefinitely());
        assertFalse(repository.validateToken(crossTenantReplay).await().indefinitely());
    }

    @Test
    void verifySignatureAcceptsStoredMatchingToken() {
        TokenRepository repository = new TokenRepository();
        DefaultExecutionTokenService service = service(repository);
        ExecutionToken token = service.issue(RUN_ID, NODE_ID, 1).await().indefinitely();

        assertTrue(service.verifySignature(completedResult(token), token.value()).await().indefinitely());
    }

    @Test
    void verifySignatureRejectsCrossTenantReplay() {
        TokenRepository repository = new TokenRepository();
        DefaultExecutionTokenService service = service(repository);
        ExecutionToken token = service.issue(RUN_ID, TENANT_ID, NODE_ID, 1).await().indefinitely();
        ExecutionToken replay = new ExecutionToken(
                token.value(),
                RUN_ID,
                TenantId.of("tenant-2"),
                NODE_ID,
                1,
                token.expiresAt());

        assertFalse(service.verifySignature(completedResult(replay), replay.value()).await().indefinitely());
    }

    @Test
    void verifySignatureRejectsExpectedTenantMismatchBeforeRepositoryLookup() {
        TokenRepository repository = new TokenRepository();
        DefaultExecutionTokenService service = service(repository);
        ExecutionToken token = service.issue(RUN_ID, TENANT_ID, NODE_ID, 1).await().indefinitely();
        repository.validationCalls = 0;

        assertFalse(service.verifySignature(
                completedResult(token),
                TenantId.of("tenant-2"),
                token.value()).await().indefinitely());
        assertEquals(0, repository.validationCalls);
    }

    @Test
    void verifySignatureAllowsExpectedTenantForLegacyNullTenantToken() {
        TokenRepository repository = new TokenRepository();
        DefaultExecutionTokenService service = service(repository);
        ExecutionToken token = service.issue(RUN_ID, NODE_ID, 1).await().indefinitely();

        assertTrue(service.verifySignature(completedResult(token), TENANT_ID, token.value()).await().indefinitely());
    }

    @Test
    void verifySignatureRejectsMismatchedSignatureBeforeRepositoryLookup() {
        TokenRepository repository = new TokenRepository();
        DefaultExecutionTokenService service = service(repository);
        ExecutionToken token = service.issue(RUN_ID, NODE_ID, 1).await().indefinitely();
        repository.validationCalls = 0;

        assertFalse(service.verifySignature(completedResult(token), "wrong-signature").await().indefinitely());
        assertEquals(0, repository.validationCalls);
    }

    @Test
    void verifySignatureRejectsUnstoredExpiredOrMissingTokens() {
        TokenRepository repository = new TokenRepository();
        DefaultExecutionTokenService service = service(repository);
        ExecutionToken unstored = new ExecutionToken(
                "unstored-token",
                RUN_ID,
                NODE_ID,
                1,
                Instant.now().plusSeconds(60));
        ExecutionToken expired = new ExecutionToken(
                "expired-token",
                RUN_ID,
                NODE_ID,
                1,
                Instant.now().minusSeconds(1));
        repository.storeToken(expired).await().indefinitely();

        assertFalse(service.verifySignature(completedResult(unstored), unstored.value()).await().indefinitely());
        assertFalse(service.verifySignature(completedResult(expired), expired.value()).await().indefinitely());
        assertFalse(service.verifySignature(null, "anything").await().indefinitely());
        assertFalse(service.verifySignature(completedResult(unstored), " ").await().indefinitely());
    }

    private static DefaultExecutionTokenService service(TokenRepository repository) {
        DefaultExecutionTokenService service = new DefaultExecutionTokenService();
        service.runRepository = repository;
        return service;
    }

    private static NodeExecutionResult completedResult(ExecutionToken token) {
        return new DefaultNodeExecutionResult(
                token.runId(),
                token.nodeId(),
                token.attempt(),
                NodeExecutionStatus.COMPLETED,
                Map.of(),
                null,
                token);
    }

    private static final class TokenRepository implements WorkflowRunRepository {
        final Map<String, StoredExecutionToken> tokens = new HashMap<>();
        int validationCalls;

        @Override
        public Uni<WorkflowRun> persist(WorkflowRun run) {
            return Uni.createFrom().item(run);
        }

        @Override
        public Uni<WorkflowRun> update(WorkflowRun run) {
            return Uni.createFrom().item(run);
        }

        @Override
        public Uni<WorkflowRun> findById(WorkflowRunId id) {
            return Uni.createFrom().nullItem();
        }

        @Override
        public Uni<WorkflowRun> findById(WorkflowRunId id, TenantId tenantId) {
            return Uni.createFrom().nullItem();
        }

        @Override
        public <T> Uni<T> withLock(WorkflowRunId runId, Function<WorkflowRun, Uni<T>> action) {
            return Uni.createFrom().failure(new UnsupportedOperationException("not used"));
        }

        @Override
        public Uni<WorkflowRunSnapshot> snapshot(WorkflowRunId runId, TenantId tenantId) {
            return Uni.createFrom().nullItem();
        }

        @Override
        public Uni<List<WorkflowRun>> query(
                TenantId tenantId,
                WorkflowDefinitionId definitionId,
                RunStatus status,
                int page,
                int size) {
            return Uni.createFrom().item(List.of());
        }

        @Override
        public Uni<Long> countActiveRuns(TenantId tenantId) {
            return Uni.createFrom().item(0L);
        }

        @Override
        public Uni<Void> storeToken(ExecutionToken token) {
            tokens.put(ExecutionTokenHash.sha256(token.value()), StoredExecutionToken.from(token));
            return Uni.createFrom().voidItem();
        }

        @Override
        public Uni<Boolean> validateToken(ExecutionToken token) {
            validationCalls++;
            StoredExecutionToken stored = tokens.get(ExecutionTokenHash.sha256(token.value()));
            return Uni.createFrom().item(stored != null && !stored.isExpired() && stored.matches(token));
        }

        @Override
        public Uni<Void> storeCallback(CallbackRegistration callback) {
            return Uni.createFrom().voidItem();
        }

        @Override
        public Uni<Boolean> validateCallback(WorkflowRunId runId, String token) {
            return Uni.createFrom().item(false);
        }

        @Override
        public Uni<Void> updateContextVariable(WorkflowRunId runId, String key, Object value) {
            return Uni.createFrom().voidItem();
        }

        @Override
        public Uni<Void> updateNodeExecution(WorkflowRunId runId, NodeId nodeId, NodeExecutionSnapshot snapshot) {
            return Uni.createFrom().voidItem();
        }
    }

    private record StoredExecutionToken(
            WorkflowRunId runId,
            TenantId tenantId,
            NodeId nodeId,
            int attempt,
            Instant expiresAt) {

        static StoredExecutionToken from(ExecutionToken token) {
            return new StoredExecutionToken(
                    token.runId(),
                    token.tenantId(),
                    token.nodeId(),
                    token.attempt(),
                    token.expiresAt());
        }

        boolean isExpired() {
            return Instant.now().isAfter(expiresAt);
        }

        boolean matches(ExecutionToken token) {
            return runId.equals(token.runId())
                    && tenantMatches(token)
                    && nodeId.equals(token.nodeId())
                    && attempt == token.attempt();
        }

        private boolean tenantMatches(ExecutionToken token) {
            return tenantId == null || tenantId.equals(token.tenantId());
        }
    }
}
