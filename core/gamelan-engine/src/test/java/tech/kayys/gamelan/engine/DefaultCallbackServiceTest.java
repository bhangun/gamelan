package tech.kayys.gamelan.engine;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.smallrye.mutiny.Uni;
import tech.kayys.gamelan.engine.callback.CallbackConfig;
import tech.kayys.gamelan.engine.callback.CallbackRegistration;
import tech.kayys.gamelan.engine.execution.BearerTokenHash;
import tech.kayys.gamelan.engine.execution.ExecutionToken;
import tech.kayys.gamelan.engine.impl.DefaultCallbackService;
import tech.kayys.gamelan.engine.node.NodeExecutionSnapshot;
import tech.kayys.gamelan.engine.node.NodeId;
import tech.kayys.gamelan.engine.repository.WorkflowRunRepository;
import tech.kayys.gamelan.engine.run.RunStatus;
import tech.kayys.gamelan.engine.tenant.TenantId;
import tech.kayys.gamelan.engine.workflow.WorkflowDefinitionId;
import tech.kayys.gamelan.engine.workflow.WorkflowRun;
import tech.kayys.gamelan.engine.workflow.WorkflowRunId;
import tech.kayys.gamelan.engine.workflow.WorkflowRunSnapshot;

import static org.junit.jupiter.api.Assertions.*;

class DefaultCallbackServiceTest {

        DefaultCallbackService callbackService;
        RecordingWorkflowRunRepository runRepository;

        @BeforeEach
        void setUp() {
                runRepository = new RecordingWorkflowRunRepository();
                callbackService = new DefaultCallbackService(runRepository);
        }

        @Test
        void register_whenCalled_createsValidRegistration() {
                // Arrange
                WorkflowRunId runId = new WorkflowRunId("run1");
                NodeId nodeId = new NodeId("node1");

                CallbackConfig config = CallbackConfig.webhook("https://example.test/callback");

                CallbackRegistration registration = callbackService.register(runId, nodeId, config)
                                .await().indefinitely();

                assertNotNull(registration.callbackToken());
                assertEquals(runId, registration.runId());
                assertNull(registration.tenantId());
                assertEquals(nodeId, registration.nodeId());
                assertEquals("https://example.test/callback", registration.callbackUrl());
                assertTrue(registration.callbackToken().matches("[A-Za-z0-9_-]{43}"));
                assertTrue(runRepository.callbacks.containsKey(BearerTokenHash.sha256(registration.callbackToken())));
                assertFalse(runRepository.callbacks.containsKey(registration.callbackToken()));
        }

        @Test
        void verify_whenRegisteredToken_returnsTrue() {
                WorkflowRunId runId = new WorkflowRunId("run1");
                CallbackRegistration registration = callbackService.register(
                                runId,
                                new NodeId("node1"),
                                CallbackConfig.webhook("https://example.test/callback"))
                                .await().indefinitely();

                assertTrue(callbackService.verify(runId, registration.callbackToken()).await().indefinitely());
        }

        @Test
        void register_withTenant_createsTenantBoundRegistration() {
                WorkflowRunId runId = new WorkflowRunId("run1");
                TenantId tenantId = TenantId.of("tenant-1");

                CallbackRegistration registration = callbackService.register(
                                runId,
                                tenantId,
                                new NodeId("node1"),
                                CallbackConfig.webhook("https://example.test/callback"))
                                .await().indefinitely();

                assertEquals(tenantId, registration.tenantId());
                assertTrue(callbackService.verify(runId, tenantId, registration.callbackToken())
                                .await().indefinitely());
                assertFalse(callbackService.verify(runId, TenantId.of("tenant-2"), registration.callbackToken())
                                .await().indefinitely());
                assertTrue(callbackService.verify(runId, registration.callbackToken()).await().indefinitely());
        }

        @Test
        void verify_whenRunDoesNotMatch_returnsFalse() {
                CallbackRegistration registration = callbackService.register(
                                new WorkflowRunId("run1"),
                                new NodeId("node1"),
                                CallbackConfig.webhook("https://example.test/callback"))
                                .await().indefinitely();

                assertFalse(callbackService.verify(new WorkflowRunId("run2"), registration.callbackToken())
                                .await().indefinitely());
        }

        @Test
        void verify_whenTokenExpired_returnsFalse() {
                WorkflowRunId runId = new WorkflowRunId("run1");
                CallbackRegistration registration = new CallbackRegistration(
                                "expired-callback-token",
                                runId,
                                new NodeId("node1"),
                                "https://example.test/callback",
                                Instant.now().minusMillis(1));
                runRepository.storeCallback(registration).await().indefinitely();

                assertFalse(callbackService.verify(runId, registration.callbackToken()).await().indefinitely());
        }

        @Test
        void verify_whenInvalidToken_returnsFalse() {
                WorkflowRunId runId = new WorkflowRunId("run1");

                assertFalse(callbackService.verify(runId, "not-registered-token").await().indefinitely());
        }

        @Test
        void verify_whenNullToken_returnsFalse() {
                assertFalse(callbackService.verify(new WorkflowRunId("run1"), null).await().indefinitely());
        }

        @Test
        void verify_whenEmptyToken_returnsFalse() {
                assertFalse(callbackService.verify(new WorkflowRunId("run1"), "").await().indefinitely());
        }

        @Test
        void verify_whenBlankToken_returnsFalse() {
                assertFalse(callbackService.verify(new WorkflowRunId("run1"), "   ").await().indefinitely());
        }

        private static final class RecordingWorkflowRunRepository implements WorkflowRunRepository {
                final Map<String, StoredCallbackRegistration> callbacks = new HashMap<>();

                @Override
                public Uni<WorkflowRun> persist(WorkflowRun run) {
                        throw new UnsupportedOperationException();
                }

                @Override
                public Uni<WorkflowRun> update(WorkflowRun run) {
                        throw new UnsupportedOperationException();
                }

                @Override
                public Uni<WorkflowRun> findById(WorkflowRunId id) {
                        throw new UnsupportedOperationException();
                }

                @Override
                public Uni<WorkflowRun> findById(WorkflowRunId id, TenantId tenantId) {
                        throw new UnsupportedOperationException();
                }

                @Override
                public <T> Uni<T> withLock(WorkflowRunId runId, Function<WorkflowRun, Uni<T>> action) {
                        throw new UnsupportedOperationException();
                }

                @Override
                public Uni<WorkflowRunSnapshot> snapshot(WorkflowRunId runId, TenantId tenantId) {
                        throw new UnsupportedOperationException();
                }

                @Override
                public Uni<List<WorkflowRun>> query(
                                TenantId tenantId,
                                WorkflowDefinitionId definitionId,
                                RunStatus status,
                                int page,
                                int size) {
                        throw new UnsupportedOperationException();
                }

                @Override
                public Uni<Long> countActiveRuns(TenantId tenantId) {
                        throw new UnsupportedOperationException();
                }

                @Override
                public Uni<Void> storeToken(ExecutionToken token) {
                        throw new UnsupportedOperationException();
                }

                @Override
                public Uni<Boolean> validateToken(ExecutionToken token) {
                        throw new UnsupportedOperationException();
                }

                @Override
                public Uni<Void> storeCallback(CallbackRegistration callback) {
                        callbacks.put(BearerTokenHash.sha256(callback.callbackToken()),
                                        StoredCallbackRegistration.from(callback));
                        return Uni.createFrom().voidItem();
                }

                @Override
                public Uni<Boolean> validateCallback(WorkflowRunId runId, String token) {
                        StoredCallbackRegistration stored = callbacks.get(BearerTokenHash.sha256(token));
                        return Uni.createFrom().item(stored != null
                                        && !stored.isExpired()
                                        && stored.runId().equals(runId));
                }

                @Override
                public Uni<Boolean> validateCallback(WorkflowRunId runId, TenantId tenantId, String token) {
                        StoredCallbackRegistration stored = callbacks.get(BearerTokenHash.sha256(token));
                        return Uni.createFrom().item(stored != null
                                        && !stored.isExpired()
                                        && stored.runId().equals(runId)
                                        && stored.tenantMatches(tenantId));
                }

                @Override
                public Uni<Void> updateContextVariable(WorkflowRunId runId, String key, Object value) {
                        throw new UnsupportedOperationException();
                }

                @Override
                public Uni<Void> updateNodeExecution(
                                WorkflowRunId runId,
                                NodeId nodeId,
                                NodeExecutionSnapshot snapshot) {
                        throw new UnsupportedOperationException();
                }
        }

        private record StoredCallbackRegistration(
                        WorkflowRunId runId,
                        TenantId tenantId,
                        Instant expiresAt) {

                static StoredCallbackRegistration from(CallbackRegistration callback) {
                        return new StoredCallbackRegistration(callback.runId(), callback.tenantId(), callback.expiresAt());
                }

                boolean isExpired() {
                        return Instant.now().isAfter(expiresAt);
                }

                boolean tenantMatches(TenantId expectedTenantId) {
                        return tenantId == null || tenantId.equals(expectedTenantId);
                }
        }
}
