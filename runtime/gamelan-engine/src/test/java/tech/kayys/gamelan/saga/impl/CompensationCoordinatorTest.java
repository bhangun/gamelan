package tech.kayys.gamelan.saga.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static tech.kayys.gamelan.engine.saga.CompensationHistoryMetadata.*;

import java.util.ArrayList;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.smallrye.mutiny.Uni;
import tech.kayys.gamelan.core.saga.impl.CompensationCoordinator;
import tech.kayys.gamelan.core.workflow.WorkflowDefinitionRegistry;
import tech.kayys.gamelan.engine.callback.CallbackRegistration;
import tech.kayys.gamelan.engine.error.ErrorInfo;
import tech.kayys.gamelan.engine.event.ExecutionEvent;
import tech.kayys.gamelan.engine.execution.ExecutionHistory;
import tech.kayys.gamelan.engine.execution.ExecutionHistoryRepository;
import tech.kayys.gamelan.engine.execution.ExecutionToken;
import tech.kayys.gamelan.engine.node.NodeDefinition;
import tech.kayys.gamelan.engine.node.NodeId;
import tech.kayys.gamelan.engine.node.NodeType;
import tech.kayys.gamelan.engine.node.NodeExecutionSnapshot;
import tech.kayys.gamelan.engine.repository.WorkflowRunRepository;
import tech.kayys.gamelan.engine.run.RetryPolicy;
import tech.kayys.gamelan.engine.run.RunStatus;
import tech.kayys.gamelan.engine.run.Transition;
import tech.kayys.gamelan.engine.saga.CompensationEventTypes;
import tech.kayys.gamelan.engine.saga.CompensationPolicy;
import tech.kayys.gamelan.engine.saga.CompensationResult;
import tech.kayys.gamelan.engine.saga.CompensationStrategy;
import tech.kayys.gamelan.engine.tenant.TenantId;
import tech.kayys.gamelan.engine.workflow.WorkflowDefinition;
import tech.kayys.gamelan.engine.workflow.WorkflowDefinitionId;
import tech.kayys.gamelan.engine.workflow.WorkflowMode;
import tech.kayys.gamelan.engine.workflow.WorkflowRun;
import tech.kayys.gamelan.engine.workflow.WorkflowRunId;
import tech.kayys.gamelan.engine.workflow.WorkflowRunSnapshot;

/**
 * Tests for CompensationCoordinator (Saga Pattern)
 */
class CompensationCoordinatorTest {

        private static final TenantId TENANT = TenantId.of("tenant-1");
        private static final WorkflowDefinitionId DEFINITION_ID = WorkflowDefinitionId.of("def-1");

        private final StaticDefinitionRegistry definitionRegistry = new StaticDefinitionRegistry();
        private final CompensationCoordinator coordinator = new CompensationCoordinator();

        private WorkflowRun failedRun;
        private WorkflowDefinition definition;
        private NodeId node1;
        private NodeId node2;
        private NodeId node3;

        @BeforeEach
        void setUp() {
                injectDefinitionRegistry(coordinator, definitionRegistry);
                node1 = NodeId.of("node-1");
                node2 = NodeId.of("node-2");
                node3 = NodeId.of("node-3");
                definition = createWorkflowDefinition();
                failedRun = createFailedWorkflowRun();
        }

        @Test
        void compensate_withNoPolicy_returnsSuccess() {
                WorkflowDefinition defWithoutPolicy = createWorkflowDefinition(
                                CompensationPolicy.disabled(),
                                createNodeDefinitions(Map.of()));
                definitionRegistry.definition = defWithoutPolicy;

                CompensationResult result = coordinator.compensate(failedRun)
                                .await().atMost(Duration.ofSeconds(5));

                assertTrue(result.success());
                assertEquals("No compensation needed", result.message());
        }

        @Test
        void compensate_withNoCompletedNodes_returnsSuccess() {
                WorkflowRun runWithoutNodes = createRunFailedBeforeAnyNodeCompletes();
                definitionRegistry.definition = definition;

                CompensationResult result = coordinator.compensate(runWithoutNodes)
                                .await().atMost(Duration.ofSeconds(5));

                assertTrue(result.success());
                assertEquals("No nodes to compensate", result.message());
        }

        @Test
        void compensate_rejectsNonCompensableRunStatus() {
                WorkflowRun completedRun = createCompletedWorkflowRun();

                CompensationResult result = coordinator.compensate(completedRun)
                                .await().atMost(Duration.ofSeconds(5));

                assertFalse(result.success());
                assertEquals("Compensation cannot run for workflow status COMPLETED", result.message());
        }

        @Test
        void compensate_withSequentialStrategy_compensatesInReverseOrder() {
                CompensationPolicy policy = new CompensationPolicy(true, CompensationStrategy.SEQUENTIAL,
                                Duration.ofMinutes(5), true, 3);
                WorkflowDefinition defWithPolicy = createWorkflowDefinition(
                                policy,
                                createNodeDefinitions(Map.of("compensationHandler", "rollback-handler")));
                definitionRegistry.definition = defWithPolicy;

                CompensationResult result = coordinator.compensate(failedRun)
                                .await().atMost(Duration.ofSeconds(5));

                assertTrue(result.success());
                assertEquals("Sequential compensation completed", result.message());
        }

        @Test
        void compensate_withSequentialStrategyStopsOnFirstFailureWhenConfigured() {
                RecordingCompensationCoordinator recordingCoordinator = new RecordingCompensationCoordinator(
                                Set.of(node2));
                injectDefinitionRegistry(recordingCoordinator, definitionRegistry);
                CompensationPolicy policy = new CompensationPolicy(true, CompensationStrategy.SEQUENTIAL,
                                Duration.ofMinutes(5), true, 3);
                definitionRegistry.definition = createWorkflowDefinition(
                                policy,
                                createNodeDefinitions(Map.of("compensationHandler", "rollback-handler")));

                CompensationResult result = recordingCoordinator.compensate(failedRun)
                                .await().atMost(Duration.ofSeconds(5));

                assertFalse(result.success());
                assertEquals("Sequential compensation failed: node-2: planned failure", result.message());
                assertEquals(List.of(node2), recordingCoordinator.attemptedNodes);
        }

        @Test
        void compensate_withSequentialStrategyContinuesAfterFailureWhenConfigured() {
                RecordingCompensationCoordinator recordingCoordinator = new RecordingCompensationCoordinator(
                                Set.of(node2));
                injectDefinitionRegistry(recordingCoordinator, definitionRegistry);
                CompensationPolicy policy = new CompensationPolicy(true, CompensationStrategy.SEQUENTIAL,
                                Duration.ofMinutes(5), false, 3);
                definitionRegistry.definition = createWorkflowDefinition(
                                policy,
                                createNodeDefinitions(Map.of("compensationHandler", "rollback-handler")));

                CompensationResult result = recordingCoordinator.compensate(failedRun)
                                .await().atMost(Duration.ofSeconds(5));

                assertFalse(result.success());
                assertEquals("Sequential compensation failed: node-2: planned failure", result.message());
                assertEquals(List.of(node2, node1), recordingCoordinator.attemptedNodes);
        }

        @Test
        void compensate_withSequentialStrategyAppliesPolicyTimeout() {
                SlowCompensationCoordinator slowCoordinator = new SlowCompensationCoordinator(
                                node2,
                                Duration.ofMillis(150));
                injectDefinitionRegistry(slowCoordinator, definitionRegistry);
                CompensationPolicy policy = new CompensationPolicy(true, CompensationStrategy.SEQUENTIAL,
                                Duration.ofMillis(20), true, 0);
                definitionRegistry.definition = createWorkflowDefinition(
                                policy,
                                createNodeDefinitions(Map.of("compensationHandler", "rollback-handler")));

                CompensationResult result = slowCoordinator.compensate(failedRun)
                                .await().atMost(Duration.ofSeconds(5));

                assertFalse(result.success());
                assertTrue(result.message().contains(
                                "node-2: Compensation timed out for node node-2"));
                assertEquals(List.of(node2), slowCoordinator.attemptedNodes);
        }

        @Test
        void compensate_withSequentialStrategyRetriesTransientFailures() {
                FlakyCompensationCoordinator flakyCoordinator = new FlakyCompensationCoordinator(node2, 1);
                injectDefinitionRegistry(flakyCoordinator, definitionRegistry);
                CompensationPolicy policy = new CompensationPolicy(true, CompensationStrategy.SEQUENTIAL,
                                Duration.ofMinutes(5), true, 1);
                definitionRegistry.definition = createWorkflowDefinition(
                                policy,
                                createNodeDefinitions(Map.of("compensationHandler", "rollback-handler")));

                CompensationResult result = flakyCoordinator.compensate(failedRun)
                                .await().atMost(Duration.ofSeconds(5));

                assertTrue(result.success());
                assertEquals("Sequential compensation completed", result.message());
                assertEquals(List.of(node2, node2, node1), flakyCoordinator.attemptedNodes);
        }

        @Test
        void compensate_withActiveCompensationUsesOnlyRemainingNodes() {
                RecordingCompensationCoordinator recordingCoordinator = new RecordingCompensationCoordinator(Set.of());
                injectDefinitionRegistry(recordingCoordinator, definitionRegistry);
                CompensationPolicy policy = new CompensationPolicy(true, CompensationStrategy.SEQUENTIAL,
                                Duration.ofMinutes(5), true, 3);
                definitionRegistry.definition = createWorkflowDefinition(
                                policy,
                                createNodeDefinitions(Map.of("compensationHandler", "rollback-handler")));
                WorkflowRun run = createCompensatingWorkflowRun();
                run.compensateNode(node2);

                CompensationResult result = recordingCoordinator.compensate(run)
                                .await().atMost(Duration.ofSeconds(5));

                assertTrue(result.success());
                assertEquals(List.of(node1), recordingCoordinator.attemptedNodes);
        }

        @Test
        void compensate_withActiveCompensationPersistsEachSuccessfulNodeProgress() {
                DurableRecordingCompensationCoordinator recordingCoordinator = new DurableRecordingCompensationCoordinator(
                                Set.of());
                injectDefinitionRegistry(recordingCoordinator, definitionRegistry);
                CompensationPolicy policy = new CompensationPolicy(true, CompensationStrategy.SEQUENTIAL,
                                Duration.ofMinutes(5), true, 0);
                definitionRegistry.definition = createWorkflowDefinition(
                                policy,
                                createNodeDefinitions(Map.of("compensationHandler", "rollback-handler")));
                WorkflowRun run = createCompensatingWorkflowRun();
                recordingCoordinator.runRepository.run = run;

                CompensationResult result = recordingCoordinator.compensate(run)
                                .await().atMost(Duration.ofSeconds(5));

                assertTrue(result.success());
                assertEquals(RunStatus.COMPENSATING, run.getStatus());
                assertTrue(run.getCompensationState().isComplete());
                assertEquals(List.of(), run.getCompensationState().nodesToCompensate());
                assertEquals(List.of(), run.getCompensationState().compensationClaims());
                assertEquals(List.of(node2, node1), run.getCompensationState().compensatedNodes());
                assertEquals(4, recordingCoordinator.runRepository.updateCount);
                assertEquals(4, recordingCoordinator.historyRepository.appends.size());
                List<HistoryAppend> claimAppends = historyAppendsOfType(
                                recordingCoordinator,
                                CompensationEventTypes.COMPENSATION_NODE_CLAIMED);
                assertEquals(2, claimAppends.size());
                assertEquals("node-2", claimAppends.get(0).metadata().get(NODE_ID));
                assertEquals("PT6M", claimAppends.get(0).metadata().get(CLAIM_LEASE));
                assertEquals(360000L, claimAppends.get(0).metadata().get(CLAIM_LEASE_MILLIS));
                List<HistoryAppend> completionAppends = historyAppendsOfType(
                                recordingCoordinator,
                                CompensationEventTypes.COMPENSATION_NODE_COMPLETED);
                assertEquals(2, completionAppends.size());
                assertEquals(CompensationEventTypes.COMPENSATION_NODE_COMPLETED, completionAppends.get(0).type());
                assertEquals("node-2", completionAppends.get(0).metadata().get(NODE_ID));
                assertEquals(List.of("node-1"),
                                completionAppends.get(0).metadata()
                                                .get(NODES_TO_COMPENSATE));
                assertEquals(List.of("node-2"),
                                completionAppends.get(0).metadata()
                                                .get(COMPENSATED_NODES));
                assertEquals(List.of(),
                                completionAppends.get(1).metadata()
                                                .get(NODES_TO_COMPENSATE));
                assertEquals(List.of("node-2", "node-1"),
                                completionAppends.get(1).metadata()
                                                .get(COMPENSATED_NODES));
                assertEquals(Set.of(
                                new CompensationMarker(run.getId(), TENANT, node2),
                                new CompensationMarker(run.getId(), TENANT, node1)),
                                recordingCoordinator.historyRepository.processedCompensationNodes);
                assertTrue(recordingCoordinator.historyRepository
                                .isCompensationNodeProcessed(run.getId(), TENANT, node2)
                                .await()
                                .indefinitely());
                assertTrue(recordingCoordinator.historyRepository
                                .isCompensationNodeProcessed(run.getId(), TENANT, node1)
                                .await()
                                .indefinitely());
        }

        @Test
        void compensate_withStaleActiveCompensationSkipsAlreadyPersistedNodeBeforeHandlerExecution() {
                DurableRecordingCompensationCoordinator recordingCoordinator = new DurableRecordingCompensationCoordinator(
                                Set.of());
                injectDefinitionRegistry(recordingCoordinator, definitionRegistry);
                CompensationPolicy policy = new CompensationPolicy(true, CompensationStrategy.SEQUENTIAL,
                                Duration.ofMinutes(5), true, 0);
                definitionRegistry.definition = createWorkflowDefinition(
                                policy,
                                List.of(
                                                createNodeDefinition(node1,
                                                                Map.of("compensationHandler", "rollback-handler")),
                                                createNodeDefinition(node2,
                                                                Map.of("compensationHandler", "rollback-handler")),
                                                createNodeDefinition(
                                                                node3,
                                                                Map.of(),
                                                                List.of(node1, node2),
                                                                true)));
                WorkflowRun staleRun = createCompensatingWorkflowRun();
                WorkflowRun persistedRun = WorkflowRun.restore(staleRun.createSnapshot(), definitionRegistry.definition);
                persistedRun.compensateNode(node2);
                recordingCoordinator.runRepository.run = persistedRun;

                CompensationResult result = recordingCoordinator.compensate(staleRun)
                                .await().atMost(Duration.ofSeconds(5));

                assertTrue(result.success());
                assertEquals("Sequential compensation completed", result.message());
                assertEquals(List.of(node1), recordingCoordinator.attemptedNodes);
                assertTrue(persistedRun.getCompensationState().isComplete());
                assertEquals(List.of(), persistedRun.getCompensationState().compensationClaims());
                assertEquals(List.of(node2, node1), persistedRun.getCompensationState().compensatedNodes());
                assertEquals(2, recordingCoordinator.runRepository.updateCount);
                assertEquals(3, recordingCoordinator.historyRepository.appends.size());
                List<HistoryAppend> skippedAppends = historyAppendsOfType(
                                recordingCoordinator,
                                CompensationEventTypes.COMPENSATION_NODE_SKIPPED);
                assertEquals(1, skippedAppends.size());
                assertEquals(SKIP_REASON_ALREADY_COMPENSATED, skippedAppends.getFirst().message());
                assertEquals("node-2", skippedAppends.getFirst().metadata().get(NODE_ID));
                assertEquals(SKIP_REASON_ALREADY_COMPENSATED, skippedAppends.getFirst().metadata().get(SKIP_REASON));
                assertEquals(List.of("node-1"),
                                skippedAppends.getFirst().metadata().get(NODES_TO_COMPENSATE));
                assertEquals(List.of("node-2"),
                                skippedAppends.getFirst().metadata().get(COMPENSATED_NODES));
                assertEquals(1, historyAppendsOfType(
                                recordingCoordinator,
                                CompensationEventTypes.COMPENSATION_NODE_CLAIMED).size());
                List<HistoryAppend> completionAppends = historyAppendsOfType(
                                recordingCoordinator,
                                CompensationEventTypes.COMPENSATION_NODE_COMPLETED);
                assertEquals(1, completionAppends.size());
                assertEquals("node-1", completionAppends.getFirst().metadata()
                                .get(NODE_ID));
                assertEquals(Set.of(
                                new CompensationMarker(persistedRun.getId(), TENANT, node2),
                                new CompensationMarker(persistedRun.getId(), TENANT, node1)),
                                recordingCoordinator.historyRepository.processedCompensationNodes);
        }

        @Test
        void compensate_withActiveCompensationClaimStopsSequentialCoordinatorWithoutDuplicateSideEffects() {
                DurableRecordingCompensationCoordinator recordingCoordinator = new DurableRecordingCompensationCoordinator(
                                Set.of());
                injectDefinitionRegistry(recordingCoordinator, definitionRegistry);
                CompensationPolicy policy = new CompensationPolicy(true, CompensationStrategy.SEQUENTIAL,
                                Duration.ofMinutes(5), true, 0);
                definitionRegistry.definition = createWorkflowDefinition(
                                policy,
                                List.of(
                                                createNodeDefinition(node1,
                                                                Map.of("compensationHandler", "rollback-handler")),
                                                createNodeDefinition(node2,
                                                                Map.of("compensationHandler", "rollback-handler")),
                                                createNodeDefinition(
                                                                node3,
                                                                Map.of(),
                                                                List.of(node1, node2),
                                                true)));
                WorkflowRun run = createCompensatingWorkflowRun();
                java.time.Instant claimedAt = java.time.Instant.now();
                run.claimCompensationNode(
                                node2,
                                "other-coordinator:claim-1",
                                claimedAt,
                                Duration.ofMinutes(5));
                recordingCoordinator.runRepository.run = run;

                CompensationResult result = recordingCoordinator.compensate(run)
                                .await().atMost(Duration.ofSeconds(5));

                assertTrue(result.success());
                assertEquals("Node compensation already claimed", result.message());
                assertEquals(List.of(), recordingCoordinator.attemptedNodes);
                assertEquals(List.of(node2, node1), run.getCompensationState().nodesToCompensate());
                assertEquals(1, run.getCompensationState().compensationClaims().size());
                assertEquals("other-coordinator:claim-1",
                                run.getCompensationState().compensationClaims().getFirst().claimId());
                assertEquals(0, recordingCoordinator.runRepository.updateCount);
                assertEquals(1, recordingCoordinator.historyRepository.appends.size());
                HistoryAppend skippedAppend = historyAppendsOfType(
                                recordingCoordinator,
                                CompensationEventTypes.COMPENSATION_NODE_CLAIM_SKIPPED).getFirst();
                assertEquals("other-coordinator:claim-1", skippedAppend.message());
                assertEquals("node-2", skippedAppend.metadata().get(NODE_ID));
                assertEquals(SKIP_REASON_ACTIVE_CLAIM, skippedAppend.metadata().get(SKIP_REASON));
                assertEquals("other-coordinator:claim-1", skippedAppend.metadata().get(ACTIVE_CLAIM_ID));
                assertEquals("other-coordinator", skippedAppend.metadata().get(ACTIVE_CLAIM_OWNER_ID));
                assertEquals(claimedAt.toString(), skippedAppend.metadata().get(ACTIVE_CLAIMED_AT));
                assertEquals(claimedAt.plus(Duration.ofMinutes(5)).toString(),
                                skippedAppend.metadata().get(ACTIVE_CLAIM_EXPIRES_AT));
        }

        @Test
        void compensate_withExpiredCompensationClaimTakesOverAndAuditsExpiredClaim() {
                ClaimLeaseRecordingCompensationCoordinator recordingCoordinator =
                                new ClaimLeaseRecordingCompensationCoordinator(
                                                Duration.ofSeconds(45),
                                                "takeover coordinator");
                injectDefinitionRegistry(recordingCoordinator, definitionRegistry);
                CompensationPolicy policy = new CompensationPolicy(true, CompensationStrategy.SEQUENTIAL,
                                Duration.ZERO, true, 0);
                definitionRegistry.definition = createWorkflowDefinition(
                                policy,
                                createNodeDefinitions(Map.of("compensationHandler", "rollback-handler")));
                WorkflowRun run = createCompensatingWorkflowRun();
                java.time.Instant claimedAt = java.time.Instant.now().minus(Duration.ofMinutes(10));
                run.claimCompensationNode(
                                node2,
                                "expired-coordinator:claim-old",
                                claimedAt,
                                Duration.ofMinutes(5));
                recordingCoordinator.runRepository.run = run;

                CompensationResult result = recordingCoordinator.compensate(run)
                                .await().atMost(Duration.ofSeconds(5));

                assertTrue(result.success());
                assertEquals(List.of(node2, node1), recordingCoordinator.attemptedNodes);
                assertEquals(List.of(), run.getCompensationState().compensationClaims());
                assertEquals(4, recordingCoordinator.runRepository.updateCount);
                List<HistoryAppend> expiredAppends = historyAppendsOfType(
                                recordingCoordinator,
                                CompensationEventTypes.COMPENSATION_NODE_CLAIM_EXPIRED);
                assertEquals(1, expiredAppends.size());
                HistoryAppend expiredAppend = expiredAppends.getFirst();
                assertEquals("expired-coordinator:claim-old", expiredAppend.message());
                assertEquals("node-2", expiredAppend.metadata().get(NODE_ID));
                assertEquals(TAKEOVER_REASON_EXPIRED_CLAIM, expiredAppend.metadata().get(TAKEOVER_REASON));
                assertEquals("takeover-coordinator", expiredAppend.metadata().get(COORDINATOR_ID));
                assertEquals("expired-coordinator:claim-old", expiredAppend.metadata().get(EXPIRED_CLAIM_ID));
                assertEquals("expired-coordinator", expiredAppend.metadata().get(EXPIRED_CLAIM_OWNER_ID));
                assertEquals(claimedAt.toString(), expiredAppend.metadata().get(EXPIRED_CLAIMED_AT));
                assertEquals(claimedAt.plus(Duration.ofMinutes(5)).toString(),
                                expiredAppend.metadata().get(EXPIRED_CLAIM_EXPIRES_AT));
                List<HistoryAppend> claimAppends = historyAppendsOfType(
                                recordingCoordinator,
                                CompensationEventTypes.COMPENSATION_NODE_CLAIMED);
                assertEquals(2, claimAppends.size());
                assertEquals("node-2", claimAppends.getFirst().metadata().get(NODE_ID));
                assertTrue(((String) claimAppends.getFirst().metadata().get(CLAIM_ID))
                                .startsWith("takeover-coordinator:"));
                assertEquals(2, historyAppendsOfType(
                                recordingCoordinator,
                                CompensationEventTypes.COMPENSATION_NODE_COMPLETED).size());
        }

        @Test
        void compensate_withActiveCompensationFailureReleasesClaimAndAuditsRelease() {
                DurableRecordingCompensationCoordinator recordingCoordinator = new DurableRecordingCompensationCoordinator(
                                Set.of(node2));
                injectDefinitionRegistry(recordingCoordinator, definitionRegistry);
                CompensationPolicy policy = new CompensationPolicy(true, CompensationStrategy.SEQUENTIAL,
                                Duration.ofMinutes(5), true, 0);
                definitionRegistry.definition = createWorkflowDefinition(
                                policy,
                                createNodeDefinitions(Map.of("compensationHandler", "rollback-handler")));
                WorkflowRun run = createCompensatingWorkflowRun();
                recordingCoordinator.runRepository.run = run;

                CompensationResult result = recordingCoordinator.compensate(run)
                                .await().atMost(Duration.ofSeconds(5));

                assertFalse(result.success());
                assertEquals("Sequential compensation failed: node-2: planned failure", result.message());
                assertEquals(List.of(node2), recordingCoordinator.attemptedNodes);
                assertEquals(List.of(), run.getCompensationState().compensationClaims());
                assertEquals(2, recordingCoordinator.runRepository.updateCount);
                List<HistoryAppend> claimAppends = historyAppendsOfType(
                                recordingCoordinator,
                                CompensationEventTypes.COMPENSATION_NODE_CLAIMED);
                List<HistoryAppend> releaseAppends = historyAppendsOfType(
                                recordingCoordinator,
                                CompensationEventTypes.COMPENSATION_NODE_CLAIM_RELEASED);
                List<HistoryAppend> failedAppends = historyAppendsOfType(
                                recordingCoordinator,
                                CompensationEventTypes.COMPENSATION_NODE_FAILED);
                assertEquals(1, claimAppends.size());
                assertEquals(1, releaseAppends.size());
                assertEquals(1, failedAppends.size());
                assertEquals("node-2", releaseAppends.getFirst().metadata().get(NODE_ID));
                assertEquals(
                                claimAppends.getFirst().metadata().get(CLAIM_ID),
                                releaseAppends.getFirst().metadata().get(CLAIM_ID));
                assertEquals("planned failure", failedAppends.getFirst().message());
                assertEquals(FAILURE_SOURCE_RESULT, failedAppends.getFirst().metadata().get(FAILURE_SOURCE));
                assertEquals("planned failure", failedAppends.getFirst().metadata().get(FAILURE_MESSAGE));
                assertEquals(
                                claimAppends.getFirst().metadata().get(CLAIM_ID),
                                failedAppends.getFirst().metadata().get(CLAIM_ID));
                assertEquals(0, historyAppendsOfType(
                                recordingCoordinator,
                                CompensationEventTypes.COMPENSATION_NODE_COMPLETED).size());
        }

        @Test
        void compensate_withActiveCompensationTimeoutAuditsFailureAndReleasesClaim() {
                SlowDurableCompensationCoordinator recordingCoordinator = new SlowDurableCompensationCoordinator(
                                node2,
                                Duration.ofMillis(150));
                injectDefinitionRegistry(recordingCoordinator, definitionRegistry);
                CompensationPolicy policy = new CompensationPolicy(true, CompensationStrategy.SEQUENTIAL,
                                Duration.ofMillis(20), true, 0);
                definitionRegistry.definition = createWorkflowDefinition(
                                policy,
                                createNodeDefinitions(Map.of("compensationHandler", "rollback-handler")));
                WorkflowRun run = createCompensatingWorkflowRun();
                recordingCoordinator.runRepository.run = run;

                CompensationResult result = recordingCoordinator.compensate(run)
                                .await().atMost(Duration.ofSeconds(5));

                assertFalse(result.success());
                assertTrue(result.message().contains(
                                "node-2: Compensation timed out for node node-2"));
                assertEquals(List.of(node2), recordingCoordinator.attemptedNodes);
                assertEquals(List.of(), run.getCompensationState().compensationClaims());
                assertEquals(2, recordingCoordinator.runRepository.updateCount);
                List<HistoryAppend> claimAppends = historyAppendsOfType(
                                recordingCoordinator,
                                CompensationEventTypes.COMPENSATION_NODE_CLAIMED);
                List<HistoryAppend> failedAppends = historyAppendsOfType(
                                recordingCoordinator,
                                CompensationEventTypes.COMPENSATION_NODE_FAILED);
                List<HistoryAppend> releaseAppends = historyAppendsOfType(
                                recordingCoordinator,
                                CompensationEventTypes.COMPENSATION_NODE_CLAIM_RELEASED);
                assertEquals(1, claimAppends.size());
                assertEquals(1, failedAppends.size());
                assertEquals(1, releaseAppends.size());
                assertEquals(FAILURE_SOURCE_EXCEPTION, failedAppends.getFirst().metadata().get(FAILURE_SOURCE));
                assertEquals(java.util.concurrent.TimeoutException.class.getName(),
                                failedAppends.getFirst().metadata().get(FAILURE_TYPE));
                assertTrue(((String) failedAppends.getFirst().metadata().get(FAILURE_MESSAGE))
                                .contains("Compensation timed out for node node-2"));
                assertEquals(
                                claimAppends.getFirst().metadata().get(CLAIM_ID),
                                failedAppends.getFirst().metadata().get(CLAIM_ID));
                assertEquals(
                                claimAppends.getFirst().metadata().get(CLAIM_ID),
                                releaseAppends.getFirst().metadata().get(CLAIM_ID));
                assertEquals(0, historyAppendsOfType(
                                recordingCoordinator,
                                CompensationEventTypes.COMPENSATION_NODE_COMPLETED).size());
        }

        @Test
        void compensate_claimLeaseUsesConfiguredDefaultWhenPolicyTimeoutIsDisabled() {
                Duration configuredLease = Duration.ofSeconds(45);
                ClaimLeaseRecordingCompensationCoordinator recordingCoordinator =
                                new ClaimLeaseRecordingCompensationCoordinator(configuredLease);
                injectDefinitionRegistry(recordingCoordinator, definitionRegistry);
                CompensationPolicy policy = new CompensationPolicy(true, CompensationStrategy.SEQUENTIAL,
                                Duration.ZERO, true, 0);
                definitionRegistry.definition = createWorkflowDefinition(
                                policy,
                                createNodeDefinitions(Map.of("compensationHandler", "rollback-handler")));
                WorkflowRun run = createCompensatingWorkflowRun();
                recordingCoordinator.runRepository.run = run;

                CompensationResult result = recordingCoordinator.compensate(run)
                                .await().atMost(Duration.ofSeconds(5));

                assertTrue(result.success());
                assertEquals(configuredLease, recordingCoordinator.observedClaimLease);
        }

        @Test
        void compensate_claimAuditUsesConfiguredCoordinatorId() {
                ClaimLeaseRecordingCompensationCoordinator recordingCoordinator =
                                new ClaimLeaseRecordingCompensationCoordinator(
                                                Duration.ofSeconds(45),
                                                "local agent 01");
                injectDefinitionRegistry(recordingCoordinator, definitionRegistry);
                CompensationPolicy policy = new CompensationPolicy(true, CompensationStrategy.SEQUENTIAL,
                                Duration.ZERO, true, 0);
                definitionRegistry.definition = createWorkflowDefinition(
                                policy,
                                createNodeDefinitions(Map.of("compensationHandler", "rollback-handler")));
                WorkflowRun run = createCompensatingWorkflowRun();
                recordingCoordinator.runRepository.run = run;

                CompensationResult result = recordingCoordinator.compensate(run)
                                .await().atMost(Duration.ofSeconds(5));

                assertTrue(result.success());
                List<HistoryAppend> claimAppends = historyAppendsOfType(
                                recordingCoordinator,
                                CompensationEventTypes.COMPENSATION_NODE_CLAIMED);
                assertEquals(2, claimAppends.size());
                assertEquals("local-agent-01", claimAppends.getFirst().metadata().get(COORDINATOR_ID));
                assertTrue(((String) claimAppends.getFirst().metadata().get(CLAIM_ID))
                                .startsWith("local-agent-01:"));
                assertEquals(
                                claimAppends.getFirst().metadata().get(CLAIM_ID),
                                claimAppends.getFirst().message());
        }

        @Test
        void compensate_claimLeaseUsesPolicyTimeoutPlusBufferBeforeConfiguredDefault() {
                Duration policyTimeout = Duration.ofSeconds(2);
                ClaimLeaseRecordingCompensationCoordinator recordingCoordinator =
                                new ClaimLeaseRecordingCompensationCoordinator(Duration.ofSeconds(45));
                injectDefinitionRegistry(recordingCoordinator, definitionRegistry);
                CompensationPolicy policy = new CompensationPolicy(true, CompensationStrategy.SEQUENTIAL,
                                policyTimeout, true, 0);
                definitionRegistry.definition = createWorkflowDefinition(
                                policy,
                                createNodeDefinitions(Map.of("compensationHandler", "rollback-handler")));
                WorkflowRun run = createCompensatingWorkflowRun();
                recordingCoordinator.runRepository.run = run;

                CompensationResult result = recordingCoordinator.compensate(run)
                                .await().atMost(Duration.ofSeconds(5));

                assertTrue(result.success());
                assertEquals(policyTimeout.plus(Duration.ofMinutes(1)), recordingCoordinator.observedClaimLease);
        }

        @Test
        void compensate_claimLeaseFallsBackWhenConfiguredDefaultIsNotPositive() {
                ClaimLeaseRecordingCompensationCoordinator recordingCoordinator =
                                new ClaimLeaseRecordingCompensationCoordinator(Duration.ZERO);
                injectDefinitionRegistry(recordingCoordinator, definitionRegistry);
                CompensationPolicy policy = new CompensationPolicy(true, CompensationStrategy.SEQUENTIAL,
                                Duration.ZERO, true, 0);
                definitionRegistry.definition = createWorkflowDefinition(
                                policy,
                                createNodeDefinitions(Map.of("compensationHandler", "rollback-handler")));
                WorkflowRun run = createCompensatingWorkflowRun();
                recordingCoordinator.runRepository.run = run;

                CompensationResult result = recordingCoordinator.compensate(run)
                                .await().atMost(Duration.ofSeconds(5));

                assertTrue(result.success());
                assertEquals(Duration.ofMinutes(15), recordingCoordinator.observedClaimLease);
        }

        @Test
        void compensate_withParallelStrategy_compensatesAllAtOnce() {
                CompensationPolicy policy = new CompensationPolicy(true, CompensationStrategy.PARALLEL,
                                Duration.ofMinutes(5), false, 3);
                WorkflowDefinition defWithPolicy = createWorkflowDefinition(
                                policy,
                                createNodeDefinitions(Map.of("compensationHandler", "rollback-handler")));
                definitionRegistry.definition = defWithPolicy;

                CompensationResult result = coordinator.compensate(failedRun)
                                .await().atMost(Duration.ofSeconds(5));

                assertTrue(result.success());
                assertEquals("Parallel compensation completed", result.message());
        }

        @Test
        void compensate_withParallelStrategyReturnsFailureWhenAnyNodeFails() {
                RecordingCompensationCoordinator recordingCoordinator = new RecordingCompensationCoordinator(
                                Set.of(node2));
                injectDefinitionRegistry(recordingCoordinator, definitionRegistry);
                CompensationPolicy policy = new CompensationPolicy(true, CompensationStrategy.PARALLEL,
                                Duration.ofMinutes(5), false, 3);
                definitionRegistry.definition = createWorkflowDefinition(
                                policy,
                                createNodeDefinitions(Map.of("compensationHandler", "rollback-handler")));

                CompensationResult result = recordingCoordinator.compensate(failedRun)
                                .await().atMost(Duration.ofSeconds(5));

                assertFalse(result.success());
                assertTrue(result.message().contains("node-2: planned failure"));
        }

        @Test
        void compensate_withCustomStrategy_fallsBackToSequential() {
                CompensationPolicy policy = new CompensationPolicy(true, CompensationStrategy.CUSTOM,
                                Duration.ofMinutes(10), true, 3);
                WorkflowDefinition defWithPolicy = createWorkflowDefinition(
                                policy,
                                createNodeDefinitions(Map.of("compensationHandler", "rollback-handler")));
                definitionRegistry.definition = defWithPolicy;

                CompensationResult result = coordinator.compensate(failedRun)
                                .await().atMost(Duration.ofSeconds(5));

                assertTrue(result.success());
                assertEquals("Sequential compensation completed", result.message());
        }

        @Test
        void needsCompensation_withFailedRunAndCompletedNodes_returnsTrue() {
                boolean needs = coordinator.needsCompensation(failedRun);

                assertTrue(needs);
        }

        @Test
        void needsCompensation_withSuccessfulRun_returnsFalse() {
                WorkflowRun successRun = createCompletedWorkflowRun();

                boolean needs = coordinator.needsCompensation(successRun);

                assertEquals(RunStatus.COMPLETED, successRun.getStatus());
                assertFalse(needs);
        }

        @Test
        void compensateNode_withNodeNotFound_returnsFailure() {
                WorkflowDefinition defWithoutNode = createWorkflowDefinition(
                                CompensationPolicy.enabledDefault(),
                                List.of());

                CompensationResult result = coordinator.compensateNode(
                                failedRun, defWithoutNode, node1).await().atMost(Duration.ofSeconds(5));

                assertFalse(result.success());
                assertEquals("Node not found", result.message());
        }

        @Test
        void compensateNode_withNoCompensationHandler_returnsSuccess() {
                WorkflowDefinition defWithNode = createWorkflowDefinition(
                                CompensationPolicy.enabledDefault(),
                                List.of(createNodeDefinition(node1, Map.of())));

                CompensationResult result = coordinator.compensateNode(
                                failedRun, defWithNode, node1).await().atMost(Duration.ofSeconds(5));

                assertTrue(result.success());
                assertEquals("No compensation needed", result.message());
        }

        @Test
        void compensateNode_withCompensationHandler_executesCompensation() {
                WorkflowDefinition defWithNode = createWorkflowDefinition(
                                CompensationPolicy.enabledDefault(),
                                List.of(createNodeDefinition(node1, Map.of("compensationHandler", "rollback-handler"))));

                CompensationResult result = coordinator.compensateNode(
                                failedRun, defWithNode, node1).await().atMost(Duration.ofSeconds(5));

                assertTrue(result.success());
                assertEquals("Node compensated", result.message());
        }

        private WorkflowRun createFailedWorkflowRun() {
                WorkflowRun run = WorkflowRun.create(
                                TENANT,
                                createWorkflowDefinition(
                                                CompensationPolicy.disabled(),
                                                List.of(
                                                                createNodeDefinition(node1,
                                                                                Map.of("compensationHandler",
                                                                                                "rollback-handler")),
                                                                createNodeDefinition(node2,
                                                                                Map.of("compensationHandler",
                                                                                                "rollback-handler")),
                                                                createNodeDefinition(
                                                                                node3,
                                                                                Map.of(),
                                                                                List.of(node1, node2),
                                                                                true))),
                                Map.of());

                run.start();
                run.startNode(node1, 1);
                run.completeNode(node1, 1, Map.of());
                run.startNode(node2, 1);
                run.completeNode(node2, 1, Map.of());
                run.startNode(node3, 1);
                run.failNode(node3, 1, error());

                assertEquals(RunStatus.FAILED, run.getStatus());
                return run;
        }

        private WorkflowRun createCompensatingWorkflowRun() {
                WorkflowRun run = WorkflowRun.create(
                                TENANT,
                                createWorkflowDefinition(
                                                CompensationPolicy.enabledDefault(),
                                                List.of(
                                                                createNodeDefinition(node1,
                                                                                Map.of("compensationHandler",
                                                                                                "rollback-handler")),
                                                                createNodeDefinition(node2,
                                                                                Map.of("compensationHandler",
                                                                                                "rollback-handler")),
                                                                createNodeDefinition(
                                                                                node3,
                                                                                Map.of(),
                                                                                List.of(node1, node2),
                                                                                true))),
                                Map.of());

                run.start();
                run.startNode(node1, 1);
                run.completeNode(node1, 1, Map.of());
                run.startNode(node2, 1);
                run.completeNode(node2, 1, Map.of());
                run.startNode(node3, 1);
                run.failNode(node3, 1, error());

                assertEquals(RunStatus.COMPENSATING, run.getStatus());
                return run;
        }

        private WorkflowRun createRunFailedBeforeAnyNodeCompletes() {
                WorkflowRun run = WorkflowRun.create(
                                TENANT,
                                createWorkflowDefinition(
                                                CompensationPolicy.disabled(),
                                                List.of(createNodeDefinition(node1, Map.of(), List.of(), true))),
                                Map.of());

                run.start();
                run.startNode(node1, 1);
                run.failNode(node1, 1, error());

                assertEquals(RunStatus.FAILED, run.getStatus());
                return run;
        }

        private WorkflowRun createCompletedWorkflowRun() {
                WorkflowRun run = WorkflowRun.create(
                                TENANT,
                                createWorkflowDefinition(
                                                CompensationPolicy.disabled(),
                                                List.of(createNodeDefinition(node1, Map.of()))),
                                Map.of());

                run.start();
                run.startNode(node1, 1);
                run.completeNode(node1, 1, Map.of());
                return run;
        }

        private WorkflowDefinition createWorkflowDefinition() {
                return createWorkflowDefinition(
                                CompensationPolicy.enabledDefault(),
                                createNodeDefinitions(Map.of("compensationHandler", "rollback-handler")));
        }

        private WorkflowDefinition createWorkflowDefinition(
                        CompensationPolicy compensationPolicy,
                        List<NodeDefinition> nodes) {
                return new WorkflowDefinition(
                                DEFINITION_ID,
                                TENANT,
                                "test-workflow",
                                "1.0.0",
                                null,
                                WorkflowMode.FLOW,
                                nodes,
                                Map.of(),
                                Map.of(),
                                null,
                                RetryPolicy.none(),
                                compensationPolicy);
        }

        private List<NodeDefinition> createNodeDefinitions(Map<String, Object> config) {
                return List.of(
                                createNodeDefinition(node1, config),
                                createNodeDefinition(node2, config));
        }

        private NodeDefinition createNodeDefinition(NodeId nodeId, Map<String, Object> config) {
                return createNodeDefinition(nodeId, config, List.of(), false);
        }

        private NodeDefinition createNodeDefinition(
                        NodeId nodeId,
                        Map<String, Object> config,
                        List<NodeId> dependsOn,
                        boolean critical) {
                return new NodeDefinition(
                                nodeId,
                                nodeId.value(),
                                NodeType.TASK,
                                "local",
                                config,
                                dependsOn,
                                List.<Transition>of(),
                                RetryPolicy.none(),
                                Duration.ZERO,
                                critical);
        }

        private static ErrorInfo error() {
                return new ErrorInfo("TEST_ERROR", "boom", "", Map.of());
        }

        private static void injectDefinitionRegistry(
                        CompensationCoordinator coordinator,
                        WorkflowDefinitionRegistry registry) {
                try {
                        var field = CompensationCoordinator.class.getDeclaredField("definitionRegistry");
                        field.setAccessible(true);
                        field.set(coordinator, registry);
                } catch (ReflectiveOperationException e) {
                        throw new AssertionError("Failed to inject definition registry", e);
                }
        }

        private static List<HistoryAppend> historyAppendsOfType(
                        DurableRecordingCompensationCoordinator coordinator,
                        String type) {
                return coordinator.historyRepository.appends.stream()
                                .filter(append -> type.equals(append.type()))
                                .toList();
        }

        private static final class StaticDefinitionRegistry extends WorkflowDefinitionRegistry {
                WorkflowDefinition definition;

                @Override
                public Uni<WorkflowDefinition> getDefinition(WorkflowDefinitionId id, TenantId tenantId) {
                        return Uni.createFrom().item(definition);
                }
        }

        private static class RecordingCompensationCoordinator extends CompensationCoordinator {
                final List<NodeId> attemptedNodes = new ArrayList<>();
                private final Set<NodeId> failedNodes;

                RecordingCompensationCoordinator(Set<NodeId> failedNodes) {
                        this.failedNodes = failedNodes;
                }

                @Override
                public Uni<CompensationResult> compensateNode(
                                WorkflowRun run,
                                WorkflowDefinition definition,
                                NodeId nodeId) {
                        attemptedNodes.add(nodeId);
                        if (failedNodes.contains(nodeId)) {
                                return Uni.createFrom().item(CompensationResult.failure("planned failure"));
                        }
                        return Uni.createFrom().item(CompensationResult.success("recorded compensation"));
                }
        }

        private static class DurableRecordingCompensationCoordinator extends RecordingCompensationCoordinator {
                final RecordingWorkflowRunRepository runRepository = new RecordingWorkflowRunRepository();
                final RecordingExecutionHistoryRepository historyRepository = new RecordingExecutionHistoryRepository();

                DurableRecordingCompensationCoordinator(Set<NodeId> failedNodes) {
                        super(failedNodes);
                }

                @Override
                protected Optional<WorkflowRunRepository> workflowRunRepository() {
                        return Optional.of(runRepository);
                }

                @Override
                protected Optional<ExecutionHistoryRepository> executionHistoryRepository() {
                        return Optional.of(historyRepository);
                }
        }

        private static final class ClaimLeaseRecordingCompensationCoordinator
                        extends DurableRecordingCompensationCoordinator {
                private final Duration configuredClaimLease;
                private final String configuredCoordinatorId;
                Duration observedClaimLease;

                ClaimLeaseRecordingCompensationCoordinator(Duration configuredClaimLease) {
                        this(configuredClaimLease, null);
                }

                ClaimLeaseRecordingCompensationCoordinator(
                                Duration configuredClaimLease,
                                String configuredCoordinatorId) {
                        super(Set.of());
                        this.configuredClaimLease = configuredClaimLease;
                        this.configuredCoordinatorId = configuredCoordinatorId;
                }

                @Override
                protected Duration configuredCompensationClaimLease() {
                        return configuredClaimLease;
                }

                @Override
                protected String configuredCompensationCoordinatorId() {
                        return configuredCoordinatorId;
                }

                @Override
                public Uni<CompensationResult> compensateNode(
                                WorkflowRun run,
                                WorkflowDefinition definition,
                                NodeId nodeId) {
                        runRepository.run.getCompensationState().compensationClaims().stream()
                                        .filter(claim -> claim.nodeId().equals(nodeId))
                                        .findFirst()
                                        .ifPresent(claim -> observedClaimLease = Duration.between(
                                                        claim.claimedAt(),
                                                        claim.expiresAt()));
                        return super.compensateNode(run, definition, nodeId);
                }
        }

        private record HistoryAppend(
                        WorkflowRunId runId,
                        String type,
                        String message,
                        Map<String, Object> metadata) {
        }

        private record CompensationMarker(
                        WorkflowRunId runId,
                        TenantId tenantId,
                        NodeId nodeId) {
        }

        private static final class RecordingExecutionHistoryRepository implements ExecutionHistoryRepository {
                final List<HistoryAppend> appends = new ArrayList<>();
                final Set<CompensationMarker> processedCompensationNodes = new java.util.LinkedHashSet<>();

                @Override
                public Uni<Void> append(WorkflowRunId runId, String type, String message, Map<String, Object> metadata) {
                        appends.add(new HistoryAppend(runId, type, message, metadata));
                        return Uni.createFrom().voidItem();
                }

                @Override
                public Uni<Void> appendEvents(WorkflowRunId runId, List<ExecutionEvent> events) {
                        return Uni.createFrom().voidItem();
                }

                @Override
                public Uni<ExecutionHistory> load(WorkflowRunId runId) {
                        return Uni.createFrom().nullItem();
                }

                @Override
                public Uni<Boolean> isNodeResultProcessed(WorkflowRunId runId, NodeId nodeId, int attempt) {
                        return Uni.createFrom().item(false);
                }

                @Override
                public Uni<Boolean> markNodeResultProcessed(WorkflowRunId runId, NodeId nodeId, int attempt) {
                        return Uni.createFrom().item(true);
                }

                @Override
                public Uni<Boolean> isCompensationNodeProcessed(WorkflowRunId runId, NodeId nodeId) {
                        return Uni.createFrom().item(
                                        processedCompensationNodes.contains(new CompensationMarker(runId, null,
                                                        nodeId)));
                }

                @Override
                public Uni<Boolean> isCompensationNodeProcessed(
                                WorkflowRunId runId,
                                TenantId tenantId,
                                NodeId nodeId) {
                        return Uni.createFrom().item(
                                        processedCompensationNodes.contains(new CompensationMarker(runId, null, nodeId))
                                                        || processedCompensationNodes.contains(
                                                                        new CompensationMarker(runId, tenantId,
                                                                                        nodeId)));
                }

                @Override
                public Uni<Boolean> markCompensationNodeProcessed(WorkflowRunId runId, NodeId nodeId) {
                        return Uni.createFrom().item(
                                        processedCompensationNodes.add(new CompensationMarker(runId, null, nodeId)));
                }

                @Override
                public Uni<Boolean> markCompensationNodeProcessed(
                                WorkflowRunId runId,
                                TenantId tenantId,
                                NodeId nodeId) {
                        if (processedCompensationNodes.contains(new CompensationMarker(runId, null, nodeId))) {
                                return Uni.createFrom().item(false);
                        }
                        return Uni.createFrom().item(
                                        processedCompensationNodes.add(new CompensationMarker(runId, tenantId,
                                                        nodeId)));
                }
        }

        private static final class RecordingWorkflowRunRepository implements WorkflowRunRepository {
                WorkflowRun run;
                int updateCount;

                @Override
                public Uni<WorkflowRun> persist(WorkflowRun run) {
                        this.run = run;
                        return Uni.createFrom().item(run);
                }

                @Override
                public Uni<WorkflowRun> update(WorkflowRun run) {
                        updateCount++;
                        this.run = run;
                        return Uni.createFrom().item(run);
                }

                @Override
                public Uni<WorkflowRun> findById(WorkflowRunId id) {
                        return Uni.createFrom().item(run);
                }

                @Override
                public Uni<WorkflowRun> findById(WorkflowRunId id, TenantId tenantId) {
                        return Uni.createFrom().item(run);
                }

                @Override
                public <T> Uni<T> withLock(WorkflowRunId runId, Function<WorkflowRun, Uni<T>> action) {
                        return action.apply(run);
                }

                @Override
                public <T> Uni<T> withLock(WorkflowRunId runId, TenantId tenantId, Function<WorkflowRun, Uni<T>> action) {
                        return action.apply(run);
                }

                @Override
                public Uni<WorkflowRunSnapshot> snapshot(WorkflowRunId runId, TenantId tenantId) {
                        return Uni.createFrom().item(run.createSnapshot());
                }

                @Override
                public Uni<List<WorkflowRun>> query(
                                TenantId tenantId,
                                WorkflowDefinitionId definitionId,
                                RunStatus status,
                                int page,
                                int size) {
                        return Uni.createFrom().item(List.of(run));
                }

                @Override
                public Uni<Long> countActiveRuns(TenantId tenantId) {
                        return Uni.createFrom().item(0L);
                }

                @Override
                public Uni<Void> storeToken(ExecutionToken token) {
                        return Uni.createFrom().voidItem();
                }

                @Override
                public Uni<Boolean> validateToken(ExecutionToken token) {
                        return Uni.createFrom().item(true);
                }

                @Override
                public Uni<Void> storeCallback(CallbackRegistration callback) {
                        return Uni.createFrom().voidItem();
                }

                @Override
                public Uni<Boolean> validateCallback(WorkflowRunId runId, String token) {
                        return Uni.createFrom().item(true);
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

        private static final class SlowCompensationCoordinator extends RecordingCompensationCoordinator {
                private final NodeId slowNode;
                private final Duration delay;

                SlowCompensationCoordinator(NodeId slowNode, Duration delay) {
                        super(Set.of());
                        this.slowNode = slowNode;
                        this.delay = delay;
                }

                @Override
                public Uni<CompensationResult> compensateNode(
                                WorkflowRun run,
                                WorkflowDefinition definition,
                                NodeId nodeId) {
                        attemptedNodes.add(nodeId);
                        Uni<CompensationResult> result = Uni.createFrom().item(
                                        CompensationResult.success("recorded compensation"));
                        return nodeId.equals(slowNode)
                                        ? result.onItem().delayIt().by(delay)
                                        : result;
                }
        }

        private static final class SlowDurableCompensationCoordinator extends DurableRecordingCompensationCoordinator {
                private final NodeId slowNode;
                private final Duration delay;

                SlowDurableCompensationCoordinator(NodeId slowNode, Duration delay) {
                        super(Set.of());
                        this.slowNode = slowNode;
                        this.delay = delay;
                }

                @Override
                public Uni<CompensationResult> compensateNode(
                                WorkflowRun run,
                                WorkflowDefinition definition,
                                NodeId nodeId) {
                        attemptedNodes.add(nodeId);
                        Uni<CompensationResult> result = Uni.createFrom().item(
                                        CompensationResult.success("recorded compensation"));
                        return nodeId.equals(slowNode)
                                        ? result.onItem().delayIt().by(delay)
                                        : result;
                }
        }

        private static final class FlakyCompensationCoordinator extends RecordingCompensationCoordinator {
                private final NodeId flakyNode;
                private final int failuresBeforeSuccess;
                private final Map<NodeId, Integer> attempts = new java.util.HashMap<>();

                FlakyCompensationCoordinator(NodeId flakyNode, int failuresBeforeSuccess) {
                        super(Set.of());
                        this.flakyNode = flakyNode;
                        this.failuresBeforeSuccess = failuresBeforeSuccess;
                }

                @Override
                public Uni<CompensationResult> compensateNode(
                                WorkflowRun run,
                                WorkflowDefinition definition,
                                NodeId nodeId) {
                        return Uni.createFrom().emitter(emitter -> {
                                attemptedNodes.add(nodeId);
                                int attempt = attempts.merge(nodeId, 1, Integer::sum);
                                if (nodeId.equals(flakyNode) && attempt <= failuresBeforeSuccess) {
                                        emitter.fail(new IllegalStateException("transient failure"));
                                } else {
                                        emitter.complete(CompensationResult.success("recorded compensation"));
                                }
                        });
                }
        }
}
