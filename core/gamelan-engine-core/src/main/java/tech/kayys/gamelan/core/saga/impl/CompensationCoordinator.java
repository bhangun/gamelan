package tech.kayys.gamelan.core.saga.impl;

import static tech.kayys.gamelan.engine.saga.CompensationHistoryMetadata.SKIP_REASON_ALREADY_COMPENSATED;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import tech.kayys.gamelan.engine.execution.ExecutionHistoryRepository;
import tech.kayys.gamelan.engine.node.NodeDefinition;
import tech.kayys.gamelan.engine.node.NodeId;
import tech.kayys.gamelan.engine.repository.WorkflowRunRepository;
import tech.kayys.gamelan.engine.run.RunStatus;
import tech.kayys.gamelan.engine.workflow.WorkflowDefinition;
import tech.kayys.gamelan.engine.workflow.WorkflowRun;
import tech.kayys.gamelan.engine.saga.CompensationClaim;
import tech.kayys.gamelan.engine.saga.CompensationHistoryRecord;
import tech.kayys.gamelan.engine.saga.CompensationHistoryRecords;
import tech.kayys.gamelan.engine.saga.CompensationPolicy;
import tech.kayys.gamelan.engine.saga.CompensationResult;
import tech.kayys.gamelan.engine.saga.CompensationService;
import tech.kayys.gamelan.core.workflow.WorkflowDefinitionRegistry;

/**
 * Coordinates compensation (saga pattern) for failed workflows
 */
@ApplicationScoped
public class CompensationCoordinator implements CompensationService {

        private static final Logger LOG = LoggerFactory.getLogger(CompensationCoordinator.class);
        private static final String NODE_ALREADY_COMPENSATED = "Node already compensated";
        private static final String NODE_COMPENSATION_ALREADY_CLAIMED = "Node compensation already claimed";
        private static final Duration DEFAULT_COMPENSATION_CLAIM_LEASE = Duration.ofMinutes(15);

        private final String generatedCompensationCoordinatorId = "coordinator-" + UUID.randomUUID();

        @Inject
        WorkflowDefinitionRegistry definitionRegistry;

        @Inject
        Instance<WorkflowRunRepository> runRepositories;

        @Inject
        Instance<ExecutionHistoryRepository> historyRepositories;

        @ConfigProperty(name = "gamelan.workflow.compensation.claim-lease", defaultValue = "15m")
        Duration defaultCompensationClaimLease = DEFAULT_COMPENSATION_CLAIM_LEASE;

        @ConfigProperty(name = "gamelan.workflow.compensation.coordinator-id", defaultValue = "auto")
        String configuredCompensationCoordinatorIdValue = "auto";

        /**
         * Execute compensation for a failed workflow
         */
        public Uni<CompensationResult> compensate(WorkflowRun run) {
                Objects.requireNonNull(run, "WorkflowRun cannot be null");
                LOG.info("Starting compensation for run: {}", run.getId().value());

                if (!canRunCompensation(run.getStatus())) {
                        return Uni.createFrom().item(CompensationResult.failure(
                                        "Compensation cannot run for workflow status " + run.getStatus()));
                }

                return definitionRegistry.getDefinition(run.getDefinitionId(), run.getTenantId())
                                .flatMap(definition -> {
                                        CompensationPolicy policy = definition.compensationPolicy();

                                        if (policy == null || !policy.enabled()) {
                                                LOG.warn("No compensation policy defined");
                                                return Uni.createFrom().item(
                                                                new CompensationResult(true, "No compensation needed"));
                                        }

                                        // Get remaining nodes to compensate in deterministic rollback order.
                                        List<NodeId> completedNodes = getNodesToCompensate(run);

                                        if (completedNodes.isEmpty()) {
                                                return Uni.createFrom().item(
                                                                new CompensationResult(true, "No nodes to compensate"));
                                        }

                                        return executeCompensationStrategy(
                                                        run, definition, completedNodes, policy);
                                });
        }

        /**
         * Get list of nodes that still need compensation.
         */
        private List<NodeId> getNodesToCompensate(WorkflowRun run) {
                if (run.getStatus() == RunStatus.COMPENSATING && run.getCompensationState() != null) {
                        return List.copyOf(run.getCompensationState().nodesToCompensate());
                }

                Set<NodeId> completed = new HashSet<>();

                run.getAllNodeExecutions().forEach((nodeId, execution) -> {
                        if (execution.isCompleted()) {
                                completed.add(nodeId);
                        }
                });

                List<NodeId> executionOrderedNodes = run.getExecutionPath().stream()
                                .filter(pathEntry -> pathEntry != null && !pathEntry.contains(":"))
                                .map(NodeId::of)
                                .filter(completed::contains)
                                .distinct()
                                .toList();

                List<NodeId> compensationOrder = new ArrayList<>(executionOrderedNodes);
                if (compensationOrder.isEmpty()) {
                        compensationOrder = new ArrayList<>(completed);
                }

                // Compensate in reverse execution order for saga rollback.
                Collections.reverse(compensationOrder);
                return compensationOrder;
        }

        /**
         * Execute compensation based on strategy
         */
        private Uni<CompensationResult> executeCompensationStrategy(
                        WorkflowRun run,
                        WorkflowDefinition definition,
                        List<NodeId> nodesToCompensate,
                        CompensationPolicy policy) {

                return switch (policy.strategy()) {
                        case SEQUENTIAL -> executeSequentialCompensation(
                                        run, definition, nodesToCompensate, policy);
                        case PARALLEL -> executeParallelCompensation(
                                        run, definition, nodesToCompensate, policy);
                        case CUSTOM -> executeCustomCompensation(
                                        run, definition, nodesToCompensate, policy);
                };
        }

        /**
         * Sequential compensation (one by one in reverse order)
         */
        private Uni<CompensationResult> executeSequentialCompensation(
                        WorkflowRun run,
                        WorkflowDefinition definition,
                        List<NodeId> nodesToCompensate,
                        CompensationPolicy policy) {

                LOG.info("Executing sequential compensation for {} nodes",
                                nodesToCompensate.size());

                Uni<CompensationResult> chain = Uni.createFrom().item(
                                CompensationResult.success("Starting compensation"));

                for (NodeId nodeId : nodesToCompensate) {
                        chain = chain.flatMap(previousResult -> {
                                if ((!previousResult.success() && policy.failOnCompensationError())
                                                || isNodeCompensationAlreadyClaimed(previousResult)) {
                                        return Uni.createFrom().item(previousResult);
                                }

                                return compensatePendingNodeWithPolicy(run, definition, nodeId, policy)
                                                .onFailure().recoverWithItem(error -> {
                                                        LOG.error("Compensation failed for node: {}",
                                                                        nodeId.value(), error);
                                                        return CompensationResult.failure(error.getMessage());
                                                })
                                                .map(nodeResult -> mergeSequentialResult(
                                                                previousResult,
                                                                nodeId,
                                                                nodeResult));
                        });
                }

                return chain.map(result -> result.success() && !isNodeCompensationAlreadyClaimed(result)
                                ? CompensationResult.success("Sequential compensation completed")
                                : result);
        }

        private CompensationResult mergeSequentialResult(
                        CompensationResult previousResult,
                        NodeId nodeId,
                        CompensationResult nodeResult) {
                if (isNodeCompensationAlreadyClaimed(nodeResult)) {
                        return nodeResult;
                }
                if (previousResult.success() && nodeResult.success()) {
                        return previousResult;
                }
                if (!previousResult.success() && nodeResult.success()) {
                        return previousResult;
                }

                List<String> failures = new ArrayList<>();
                appendSequentialFailure(failures, previousResult);
                appendFailure(failures, nodeId, nodeResult);
                return CompensationResult.failure("Sequential compensation failed: " + String.join("; ", failures));
        }

        /**
         * Parallel compensation (all at once)
         */
        private Uni<CompensationResult> executeParallelCompensation(
                        WorkflowRun run,
                        WorkflowDefinition definition,
                        List<NodeId> nodesToCompensate,
                        CompensationPolicy policy) {

                LOG.info("Executing parallel compensation for {} nodes",
                                nodesToCompensate.size());

                List<Uni<CompensationResult>> compensations = nodesToCompensate.stream()
                                .map(nodeId -> compensatePendingNodeWithPolicy(run, definition, nodeId, policy)
                                                .map(result -> result.success()
                                                                ? result
                                                                : CompensationResult.failure(nodeId.value() + ": "
                                                                                + failureMessage(result)))
                                                .onFailure().recoverWithItem(error -> {
                                                        LOG.error("Compensation failed for node: {}",
                                                                        nodeId.value(), error);
                                                        return CompensationResult.failure(nodeId.value() + ": "
                                                                        + error.getMessage());
                                                }))
                                .toList();

                return Uni.join().all(compensations).andFailFast()
                                .map(this::parallelResult);
        }

        private Uni<CompensationResult> compensatePendingNodeWithPolicy(
                        WorkflowRun run,
                        WorkflowDefinition definition,
                        NodeId nodeId,
                        CompensationPolicy policy) {
                return claimCompensationNodeForExecution(run, nodeId, policy)
                                .flatMap(claim -> {
                                        if (claim.status() == ClaimStatus.ALREADY_COMPENSATED) {
                                                LOG.debug("Skipping already-processed compensation node: {}",
                                                                nodeId.value());
                                                return Uni.createFrom().item(
                                                                CompensationResult.success(NODE_ALREADY_COMPENSATED));
                                        }
                                        if (claim.status() == ClaimStatus.ALREADY_CLAIMED) {
                                                LOG.debug("Skipping actively claimed compensation node: {}",
                                                                nodeId.value());
                                                return Uni.createFrom().item(
                                                                CompensationResult.success(
                                                                                NODE_COMPENSATION_ALREADY_CLAIMED));
                                        }

                                        return compensateNodeWithPolicy(run, definition, nodeId, policy)
                                                        .flatMap(nodeResult -> {
                                                                if (nodeResult == null || !nodeResult.success()) {
                                                                        return appendCompensationNodeFailed(
                                                                                        run,
                                                                                        nodeId,
                                                                                        claim.claimId().orElseThrow(),
                                                                                        nodeResult)
                                                                                        .call(() -> releaseCompensationNodeClaim(
                                                                                                        run,
                                                                                                        nodeId,
                                                                                                        claim.claimId()
                                                                                                                        .orElseThrow()))
                                                                                        .replaceWith(nodeResult);
                                                                }
                                                                return recordSuccessfulCompensationProgress(
                                                                                run,
                                                                                nodeId,
                                                                                nodeResult);
                                                        })
                                                        .onFailure()
                                                        .call(error -> appendCompensationNodeFailed(
                                                                        run,
                                                                        nodeId,
                                                                        claim.claimId().orElseThrow(),
                                                                        error)
                                                                        .call(() -> releaseCompensationNodeClaim(
                                                                                        run, nodeId,
                                                                                        claim.claimId().orElseThrow())));
                                });
        }

        private Uni<ClaimOutcome> claimCompensationNodeForExecution(
                        WorkflowRun run,
                        NodeId nodeId,
                        CompensationPolicy policy) {
                if (run.getStatus() != RunStatus.COMPENSATING || run.getCompensationState() == null) {
                        return Uni.createFrom().item(ClaimOutcome.claimed(newCompensationClaimId()));
                }

                String claimId = newCompensationClaimId();
                Instant now = Instant.now();
                Duration claimLease = compensationClaimLease(policy);
                Optional<WorkflowRunRepository> repository = workflowRunRepository();
                if (repository.isEmpty()) {
                        if (run.getCompensationState().compensatedNodes().contains(nodeId)) {
                                return appendCompensationNodeSkipped(
                                                run,
                                                nodeId,
                                                SKIP_REASON_ALREADY_COMPENSATED,
                                                now)
                                                .replaceWith(ClaimOutcome.alreadyCompensated());
                        }
                        if (!isCompensationNodeStillPending(run, nodeId)) {
                                return Uni.createFrom().item(ClaimOutcome.alreadyClaimed());
                        }
                        Optional<CompensationClaim> activeClaim = activeCompensationClaim(run, nodeId, now);
                        if (activeClaim.isPresent()) {
                                return appendCompensationNodeClaimSkipped(run, nodeId, activeClaim.get(), now)
                                                .replaceWith(ClaimOutcome.alreadyClaimed());
                        }
                        Optional<CompensationClaim> expiredClaim = expiredCompensationClaim(run, nodeId, now);
                        run.claimCompensationNode(nodeId, claimId, now, claimLease);
                        return appendCompensationNodeClaimTakenOver(
                                        run,
                                        nodeId,
                                        expiredClaim,
                                        claimId,
                                        now,
                                        claimLease)
                                        .replaceWith(ClaimOutcome.claimed(claimId));
                }

                return repository.get().withLock(run.getId(), run.getTenantId(), lockedRun -> {
                        if (lockedRun == null
                                        || lockedRun.getStatus() != RunStatus.COMPENSATING
                                        || lockedRun.getCompensationState() == null) {
                                return Uni.createFrom().item(ClaimOutcome.alreadyClaimed());
                        }
                        if (lockedRun.getCompensationState().compensatedNodes().contains(nodeId)) {
                                return markCompensationNodeProcessed(lockedRun, nodeId)
                                                .call(() -> appendCompensationNodeSkipped(
                                                                lockedRun,
                                                                nodeId,
                                                                SKIP_REASON_ALREADY_COMPENSATED,
                                                                now))
                                                .replaceWith(ClaimOutcome.alreadyCompensated());
                        }
                        if (!lockedRun.getCompensationState().nodesToCompensate().contains(nodeId)) {
                                return Uni.createFrom().item(ClaimOutcome.alreadyClaimed());
                        }
                        Optional<CompensationClaim> activeClaim = activeCompensationClaim(lockedRun, nodeId, now);
                        if (activeClaim.isPresent()) {
                                return appendCompensationNodeClaimSkipped(lockedRun, nodeId, activeClaim.get(), now)
                                                .replaceWith(ClaimOutcome.alreadyClaimed());
                        }
                        Optional<CompensationClaim> expiredClaim = expiredCompensationClaim(lockedRun, nodeId, now);
                        lockedRun.claimCompensationNode(nodeId, claimId, now, claimLease);
                        return repository.get().update(lockedRun)
                                        .call(() -> appendCompensationNodeClaimTakenOver(
                                                        lockedRun,
                                                        nodeId,
                                                        expiredClaim,
                                                        claimId,
                                                        now,
                                                        claimLease))
                                        .replaceWith(ClaimOutcome.claimed(claimId));
                });
        }

        private boolean isNodeCompensationAlreadyClaimed(CompensationResult result) {
                return result != null
                                && result.success()
                                && NODE_COMPENSATION_ALREADY_CLAIMED.equals(result.message());
        }

        private boolean isCompensationNodeStillPending(WorkflowRun run, NodeId nodeId) {
                return run.getCompensationState() != null
                                && run.getCompensationState().nodesToCompensate().contains(nodeId);
        }

        private Uni<Void> releaseCompensationNodeClaim(WorkflowRun run, NodeId nodeId, String claimId) {
                if (run.getStatus() != RunStatus.COMPENSATING || run.getCompensationState() == null) {
                        return Uni.createFrom().voidItem();
                }

                Optional<WorkflowRunRepository> repository = workflowRunRepository();
                if (repository.isEmpty()) {
                        if (!hasCompensationClaim(run, nodeId, claimId)) {
                                return Uni.createFrom().voidItem();
                        }
                        run.releaseCompensationNodeClaim(nodeId, claimId);
                        return appendCompensationNodeClaimReleased(run, nodeId, claimId);
                }

                return repository.get().withLock(run.getId(), run.getTenantId(), lockedRun -> {
                        if (lockedRun == null
                                        || lockedRun.getStatus() != RunStatus.COMPENSATING
                                        || lockedRun.getCompensationState() == null
                                        || lockedRun.getCompensationState().compensatedNodes().contains(nodeId)) {
                                return Uni.createFrom().voidItem();
                        }

                        if (!hasCompensationClaim(lockedRun, nodeId, claimId)) {
                                return Uni.createFrom().voidItem();
                        }

                        lockedRun.releaseCompensationNodeClaim(nodeId, claimId);
                        return repository.get().update(lockedRun)
                                        .call(() -> appendCompensationNodeClaimReleased(lockedRun, nodeId, claimId))
                                        .replaceWithVoid();
                });
        }

        private boolean hasCompensationClaim(WorkflowRun run, NodeId nodeId, String claimId) {
                return run.getCompensationState() != null
                                && run.getCompensationState().compensationClaims().stream()
                                                .anyMatch(claim -> claim.nodeId().equals(nodeId)
                                                                && Objects.equals(claim.claimId(), claimId));
        }

        private Optional<CompensationClaim> activeCompensationClaim(
                        WorkflowRun run,
                        NodeId nodeId,
                        Instant now) {
                if (run.getCompensationState() == null) {
                        return Optional.empty();
                }
                return run.getCompensationState().compensationClaims().stream()
                                .filter(claim -> claim.nodeId().equals(nodeId) && claim.isActive(now))
                                .findFirst();
        }

        private Optional<CompensationClaim> expiredCompensationClaim(
                        WorkflowRun run,
                        NodeId nodeId,
                        Instant now) {
                if (run.getCompensationState() == null) {
                        return Optional.empty();
                }
                return run.getCompensationState().compensationClaims().stream()
                                .filter(claim -> claim.nodeId().equals(nodeId) && !claim.isActive(now))
                                .findFirst();
        }

        private Duration compensationClaimLease(CompensationPolicy policy) {
                Duration timeout = policy != null ? policy.timeout() : null;
                if (timeout != null && !timeout.isZero() && !timeout.isNegative()) {
                        return timeout.plus(Duration.ofMinutes(1));
                }
                return positiveDuration(configuredCompensationClaimLease(), DEFAULT_COMPENSATION_CLAIM_LEASE);
        }

        protected Duration configuredCompensationClaimLease() {
                return defaultCompensationClaimLease;
        }

        protected String configuredCompensationCoordinatorId() {
                return configuredCompensationCoordinatorIdValue;
        }

        private String newCompensationClaimId() {
                return compensationCoordinatorId() + ":" + UUID.randomUUID();
        }

        private String compensationCoordinatorId() {
                String configuredId = safeCoordinatorId(configuredCompensationCoordinatorId());
                return configuredId.isEmpty() ? generatedCompensationCoordinatorId : configuredId;
        }

        private static String safeCoordinatorId(String coordinatorId) {
                if (coordinatorId == null) {
                        return "";
                }
                String trimmed = coordinatorId.trim();
                if (trimmed.isEmpty() || "auto".equalsIgnoreCase(trimmed)) {
                        return "";
                }
                return trimmed.replaceAll("\\s+", "-");
        }

        private static Duration positiveDuration(Duration duration, Duration fallback) {
                if (duration == null || duration.isZero() || duration.isNegative()) {
                        return fallback;
                }
                return duration;
        }

        private enum ClaimStatus {
                CLAIMED,
                ALREADY_COMPENSATED,
                ALREADY_CLAIMED
        }

        private record ClaimOutcome(ClaimStatus status, Optional<String> claimId) {
                static ClaimOutcome claimed(String claimId) {
                        return new ClaimOutcome(ClaimStatus.CLAIMED, Optional.of(claimId));
                }

                static ClaimOutcome alreadyCompensated() {
                        return new ClaimOutcome(ClaimStatus.ALREADY_COMPENSATED, Optional.empty());
                }

                static ClaimOutcome alreadyClaimed() {
                        return new ClaimOutcome(ClaimStatus.ALREADY_CLAIMED, Optional.empty());
                }
        }

        private Uni<CompensationResult> recordSuccessfulCompensationProgress(
                        WorkflowRun run,
                        NodeId nodeId,
                        CompensationResult nodeResult) {
                if (nodeResult == null || !nodeResult.success()) {
                        return Uni.createFrom().item(nodeResult);
                }

                return recordCompensationNodeCompleted(run, nodeId)
                                .replaceWith(nodeResult);
        }

        private Uni<Void> recordCompensationNodeCompleted(WorkflowRun run, NodeId nodeId) {
                if (run.getStatus() != RunStatus.COMPENSATING) {
                        return Uni.createFrom().voidItem();
                }

                Optional<WorkflowRunRepository> repository = workflowRunRepository();
                if (repository.isEmpty()) {
                        if (!recordInMemoryCompensationProgress(run, nodeId)) {
                                return markCompensationNodeProcessed(run, nodeId);
                        }
                        return appendCompensationNodeCompleted(run, nodeId)
                                        .call(() -> markCompensationNodeProcessed(run, nodeId));
                }

                return repository.get().withLock(run.getId(), run.getTenantId(), lockedRun -> {
                        if (lockedRun.getStatus() != RunStatus.COMPENSATING
                                        || lockedRun.getCompensationState() == null) {
                                return Uni.createFrom().voidItem();
                        }
                        if (lockedRun.getCompensationState().compensatedNodes().contains(nodeId)) {
                                return markCompensationNodeProcessed(lockedRun, nodeId);
                        }

                        lockedRun.compensateNode(nodeId);
                        return repository.get().update(lockedRun)
                                        .call(() -> appendCompensationNodeCompleted(lockedRun, nodeId))
                                        .call(() -> markCompensationNodeProcessed(lockedRun, nodeId))
                                        .replaceWithVoid();
                });
        }

        private boolean recordInMemoryCompensationProgress(WorkflowRun run, NodeId nodeId) {
                if (run.getCompensationState() == null
                                || run.getCompensationState().compensatedNodes().contains(nodeId)) {
                        return false;
                }
                run.compensateNode(nodeId);
                return true;
        }

        private Uni<Void> appendCompensationNodeCompleted(WorkflowRun run, NodeId nodeId) {
                Optional<ExecutionHistoryRepository> historyRepository = executionHistoryRepository();
                if (historyRepository.isEmpty()) {
                        return Uni.createFrom().voidItem();
                }

                CompensationHistoryRecord record = CompensationHistoryRecords.nodeCompleted(run, nodeId);
                return historyRepository.get().append(
                                run.getId(),
                                run.getTenantId(),
                                record.eventType(),
                                record.message(),
                                record.metadata());
        }

        private Uni<Void> appendCompensationNodeClaimed(
                        WorkflowRun run,
                        NodeId nodeId,
                        String claimId,
                        Instant claimedAt,
                        Duration claimLease) {
                return appendBestEffortCompensationHistory(
                                run,
                                nodeId,
                                CompensationHistoryRecords.nodeClaimed(
                                                run,
                                                nodeId,
                                                compensationCoordinatorId(),
                                                claimId,
                                                claimedAt,
                                                claimLease));
        }

        private Uni<Void> appendCompensationNodeClaimTakenOver(
                        WorkflowRun run,
                        NodeId nodeId,
                        Optional<CompensationClaim> expiredClaim,
                        String claimId,
                        Instant claimedAt,
                        Duration claimLease) {
                Uni<Void> expiredClaimAudit = expiredClaim
                                .map(claim -> appendCompensationNodeClaimExpired(run, nodeId, claim, claimedAt))
                                .orElseGet(() -> Uni.createFrom().voidItem());
                return expiredClaimAudit
                                .call(() -> appendCompensationNodeClaimed(run, nodeId, claimId, claimedAt, claimLease));
        }

        private Uni<Void> appendCompensationNodeClaimExpired(
                        WorkflowRun run,
                        NodeId nodeId,
                        CompensationClaim expiredClaim,
                        Instant detectedAt) {
                return appendBestEffortCompensationHistory(
                                run,
                                nodeId,
                                CompensationHistoryRecords.nodeClaimExpired(
                                                run,
                                                nodeId,
                                                compensationCoordinatorId(),
                                                expiredClaim,
                                                detectedAt));
        }

        private Uni<Void> appendCompensationNodeClaimReleased(
                        WorkflowRun run,
                        NodeId nodeId,
                        String claimId) {
                return appendBestEffortCompensationHistory(
                                run,
                                nodeId,
                                CompensationHistoryRecords.nodeClaimReleased(
                                                run,
                                                nodeId,
                                                compensationCoordinatorId(),
                                                claimId,
                                                Instant.now()));
        }

        private Uni<Void> appendCompensationNodeClaimSkipped(
                        WorkflowRun run,
                        NodeId nodeId,
                        CompensationClaim activeClaim,
                        Instant skippedAt) {
                return appendBestEffortCompensationHistory(
                                run,
                                nodeId,
                                CompensationHistoryRecords.nodeClaimSkipped(
                                                run,
                                                nodeId,
                                                compensationCoordinatorId(),
                                                activeClaim,
                                                skippedAt));
        }

        private Uni<Void> appendCompensationNodeFailed(
                        WorkflowRun run,
                        NodeId nodeId,
                        String claimId,
                        CompensationResult nodeResult) {
                return appendBestEffortCompensationHistory(
                                run,
                                nodeId,
                                CompensationHistoryRecords.nodeFailed(
                                                run,
                                                nodeId,
                                                compensationCoordinatorId(),
                                                claimId,
                                                nodeResult,
                                                Instant.now()));
        }

        private Uni<Void> appendCompensationNodeFailed(
                        WorkflowRun run,
                        NodeId nodeId,
                        String claimId,
                        Throwable error) {
                return appendBestEffortCompensationHistory(
                                run,
                                nodeId,
                                CompensationHistoryRecords.nodeFailed(
                                                run,
                                                nodeId,
                                                compensationCoordinatorId(),
                                                claimId,
                                                error,
                                                Instant.now()));
        }

        private Uni<Void> appendCompensationNodeSkipped(
                        WorkflowRun run,
                        NodeId nodeId,
                        String skipReason,
                        Instant skippedAt) {
                return appendBestEffortCompensationHistory(
                                run,
                                nodeId,
                                CompensationHistoryRecords.nodeSkipped(
                                                run,
                                                nodeId,
                                                compensationCoordinatorId(),
                                                skipReason,
                                                skippedAt));
        }

        private Uni<Void> appendBestEffortCompensationHistory(
                        WorkflowRun run,
                        NodeId nodeId,
                        CompensationHistoryRecord record) {
                Optional<ExecutionHistoryRepository> historyRepository = executionHistoryRepository();
                if (historyRepository.isEmpty()) {
                        return Uni.createFrom().voidItem();
                }

                return historyRepository.get().append(
                                run.getId(),
                                run.getTenantId(),
                                record.eventType(),
                                record.message(),
                                record.metadata())
                                .onFailure().invoke(error -> LOG.warn(
                                                "Failed to append {} history for run {} node {}",
                                                record.eventType(),
                                                run.getId().value(),
                                                nodeId.value(),
                                                error))
                                .onFailure().recoverWithNull();
        }

        private Uni<Void> markCompensationNodeProcessed(WorkflowRun run, NodeId nodeId) {
                Optional<ExecutionHistoryRepository> historyRepository = executionHistoryRepository();
                if (historyRepository.isEmpty()) {
                        return Uni.createFrom().voidItem();
                }

                return historyRepository.get()
                                .markCompensationNodeProcessed(run.getId(), run.getTenantId(), nodeId)
                                .replaceWithVoid();
        }

        protected Optional<WorkflowRunRepository> workflowRunRepository() {
                return optionalBean(runRepositories);
        }

        protected Optional<ExecutionHistoryRepository> executionHistoryRepository() {
                return optionalBean(historyRepositories);
        }

        private <T> Optional<T> optionalBean(Instance<T> instance) {
                if (instance == null || instance.isUnsatisfied()) {
                        return Optional.empty();
                }
                return Optional.of(instance.get());
        }

        private Uni<CompensationResult> compensateNodeWithPolicy(
                        WorkflowRun run,
                        WorkflowDefinition definition,
                        NodeId nodeId,
                        CompensationPolicy policy) {
                Uni<CompensationResult> compensation = compensateNode(run, definition, nodeId);

                Duration timeout = policy != null ? policy.timeout() : null;
                if (timeout != null && !timeout.isZero() && !timeout.isNegative()) {
                        compensation = compensation.ifNoItem().after(timeout).failWith(
                                        () -> new TimeoutException(
                                                        "Compensation timed out for node "
                                                                        + nodeId.value()
                                                                        + " after "
                                                                        + timeout));
                }

                int maxRetries = policy != null ? Math.max(0, policy.maxRetries()) : 0;
                if (maxRetries > 0) {
                        compensation = compensation.onFailure().retry().atMost(maxRetries);
                }

                return compensation;
        }

        private CompensationResult parallelResult(List<CompensationResult> results) {
                List<String> failures = new ArrayList<>();
                for (CompensationResult result : results) {
                        appendFailure(failures, result);
                }

                if (failures.isEmpty()) {
                        return CompensationResult.success("Parallel compensation completed");
                }
                return CompensationResult.failure("Parallel compensation failed: " + String.join("; ", failures));
        }

        private void appendFailure(List<String> failures, CompensationResult result) {
                if (result != null && !result.success()) {
                        failures.add(failureMessage(result));
                }
        }

        private void appendSequentialFailure(List<String> failures, CompensationResult result) {
                if (result == null || result.success()) {
                        return;
                }
                String message = failureMessage(result);
                String prefix = "Sequential compensation failed: ";
                failures.add(message.startsWith(prefix) ? message.substring(prefix.length()) : message);
        }

        private void appendFailure(List<String> failures, NodeId nodeId, CompensationResult result) {
                if (result != null && !result.success()) {
                        failures.add(nodeId.value() + ": " + failureMessage(result));
                }
        }

        private String failureMessage(CompensationResult result) {
                return result != null && result.message() != null ? result.message() : "unknown failure";
        }

        /**
         * Custom compensation (hook for extensions)
         */
        private Uni<CompensationResult> executeCustomCompensation(
                        WorkflowRun run,
                        WorkflowDefinition definition,
                        List<NodeId> nodesToCompensate,
                        CompensationPolicy policy) {

                LOG.warn("Custom compensation not implemented, using sequential");
                return executeSequentialCompensation(
                                run, definition, nodesToCompensate, policy);
        }

        /**
         * Compensate a single node (implements CompensationService)
         */
        @Override
        public Uni<CompensationResult> compensateNode(
                        WorkflowRun run,
                        WorkflowDefinition definition,
                        NodeId nodeId) {

                LOG.debug("Compensating node: {}", nodeId.value());

                // Find node definition
                Optional<NodeDefinition> nodeDefOpt = definition.findNode(nodeId);
                if (nodeDefOpt.isEmpty()) {
                        return Uni.createFrom().item(
                                        new CompensationResult(false, "Node not found"));
                }

                NodeDefinition nodeDef = nodeDefOpt.get();

                // Check if node has compensation handler
                Object compensationHandler = nodeDef.configuration().get("compensationHandler");

                if (compensationHandler == null) {
                        LOG.debug("No compensation handler for node: {}", nodeId.value());
                        return Uni.createFrom().item(
                                        new CompensationResult(true, "No compensation needed"));
                }

                // Execute compensation handler
                // In real implementation, this would invoke the compensation executor
                return Uni.createFrom().item(
                                new CompensationResult(true, "Node compensated"))
                                .onItem().delayIt().by(Duration.ofMillis(100)); // Simulate work
        }

        /**
         * Check if compensation is needed for a workflow
         */
        @Override
        public boolean needsCompensation(WorkflowRun run) {
                // Compensation is needed if workflow failed and has completed nodes
                return (run.getStatus() == RunStatus.FAILED || run.getStatus() == RunStatus.COMPENSATING)
                                && !getNodesToCompensate(run).isEmpty();
        }

        private boolean canRunCompensation(RunStatus status) {
                return status == RunStatus.FAILED
                                || status == RunStatus.CANCELLED
                                || status == RunStatus.COMPENSATING;
        }
}
