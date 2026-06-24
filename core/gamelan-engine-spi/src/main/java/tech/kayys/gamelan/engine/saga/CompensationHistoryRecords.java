package tech.kayys.gamelan.engine.saga;

import static tech.kayys.gamelan.engine.saga.CompensationEventTypes.COMPENSATION_NODE_CLAIMED;
import static tech.kayys.gamelan.engine.saga.CompensationEventTypes.COMPENSATION_NODE_CLAIM_EXPIRED;
import static tech.kayys.gamelan.engine.saga.CompensationEventTypes.COMPENSATION_NODE_CLAIM_RELEASED;
import static tech.kayys.gamelan.engine.saga.CompensationEventTypes.COMPENSATION_NODE_CLAIM_SKIPPED;
import static tech.kayys.gamelan.engine.saga.CompensationEventTypes.COMPENSATION_NODE_COMPLETED;
import static tech.kayys.gamelan.engine.saga.CompensationEventTypes.COMPENSATION_NODE_FAILED;
import static tech.kayys.gamelan.engine.saga.CompensationEventTypes.COMPENSATION_NODE_SKIPPED;
import static tech.kayys.gamelan.engine.saga.CompensationHistoryMetadata.ACTIVE_CLAIMED_AT;
import static tech.kayys.gamelan.engine.saga.CompensationHistoryMetadata.ACTIVE_CLAIM_EXPIRES_AT;
import static tech.kayys.gamelan.engine.saga.CompensationHistoryMetadata.ACTIVE_CLAIM_ID;
import static tech.kayys.gamelan.engine.saga.CompensationHistoryMetadata.ACTIVE_CLAIM_OWNER_ID;
import static tech.kayys.gamelan.engine.saga.CompensationHistoryMetadata.CLAIMED_AT;
import static tech.kayys.gamelan.engine.saga.CompensationHistoryMetadata.CLAIM_ID;
import static tech.kayys.gamelan.engine.saga.CompensationHistoryMetadata.CLAIM_LEASE;
import static tech.kayys.gamelan.engine.saga.CompensationHistoryMetadata.CLAIM_LEASE_MILLIS;
import static tech.kayys.gamelan.engine.saga.CompensationHistoryMetadata.COMPENSATED_NODES;
import static tech.kayys.gamelan.engine.saga.CompensationHistoryMetadata.COMPENSATED_NODES_COUNT;
import static tech.kayys.gamelan.engine.saga.CompensationHistoryMetadata.COMPENSATION_CLAIMS;
import static tech.kayys.gamelan.engine.saga.CompensationHistoryMetadata.COMPENSATION_CLAIMS_COUNT;
import static tech.kayys.gamelan.engine.saga.CompensationHistoryMetadata.COMPENSATION_STARTED_AT;
import static tech.kayys.gamelan.engine.saga.CompensationHistoryMetadata.COMPENSATION_STATUS;
import static tech.kayys.gamelan.engine.saga.CompensationHistoryMetadata.COORDINATOR_ID;
import static tech.kayys.gamelan.engine.saga.CompensationHistoryMetadata.DETECTED_AT;
import static tech.kayys.gamelan.engine.saga.CompensationHistoryMetadata.EXPIRES_AT;
import static tech.kayys.gamelan.engine.saga.CompensationHistoryMetadata.EXPIRED_CLAIMED_AT;
import static tech.kayys.gamelan.engine.saga.CompensationHistoryMetadata.EXPIRED_CLAIM_EXPIRES_AT;
import static tech.kayys.gamelan.engine.saga.CompensationHistoryMetadata.EXPIRED_CLAIM_ID;
import static tech.kayys.gamelan.engine.saga.CompensationHistoryMetadata.EXPIRED_CLAIM_OWNER_ID;
import static tech.kayys.gamelan.engine.saga.CompensationHistoryMetadata.FAILED_AT;
import static tech.kayys.gamelan.engine.saga.CompensationHistoryMetadata.FAILURE_MESSAGE;
import static tech.kayys.gamelan.engine.saga.CompensationHistoryMetadata.FAILURE_SOURCE;
import static tech.kayys.gamelan.engine.saga.CompensationHistoryMetadata.FAILURE_SOURCE_EXCEPTION;
import static tech.kayys.gamelan.engine.saga.CompensationHistoryMetadata.FAILURE_SOURCE_RESULT;
import static tech.kayys.gamelan.engine.saga.CompensationHistoryMetadata.FAILURE_TYPE;
import static tech.kayys.gamelan.engine.saga.CompensationHistoryMetadata.NODE_ID;
import static tech.kayys.gamelan.engine.saga.CompensationHistoryMetadata.NODES_TO_COMPENSATE;
import static tech.kayys.gamelan.engine.saga.CompensationHistoryMetadata.NODES_TO_COMPENSATE_COUNT;
import static tech.kayys.gamelan.engine.saga.CompensationHistoryMetadata.RELEASED_AT;
import static tech.kayys.gamelan.engine.saga.CompensationHistoryMetadata.SKIPPED_AT;
import static tech.kayys.gamelan.engine.saga.CompensationHistoryMetadata.SKIP_REASON;
import static tech.kayys.gamelan.engine.saga.CompensationHistoryMetadata.SKIP_REASON_ACTIVE_CLAIM;
import static tech.kayys.gamelan.engine.saga.CompensationHistoryMetadata.STATUS;
import static tech.kayys.gamelan.engine.saga.CompensationHistoryMetadata.TAKEOVER_REASON;
import static tech.kayys.gamelan.engine.saga.CompensationHistoryMetadata.TAKEOVER_REASON_EXPIRED_CLAIM;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import tech.kayys.gamelan.engine.node.NodeId;
import tech.kayys.gamelan.engine.workflow.WorkflowRun;

/**
 * Factory methods for compensation execution-history records.
 */
public final class CompensationHistoryRecords {

    private CompensationHistoryRecords() {
    }

    public static CompensationHistoryRecord nodeCompleted(WorkflowRun run, NodeId nodeId) {
        return new CompensationHistoryRecord(
                COMPENSATION_NODE_COMPLETED,
                nodeId.value(),
                completedMetadata(run, nodeId));
    }

    public static CompensationHistoryRecord nodeClaimed(
            WorkflowRun run,
            NodeId nodeId,
            String coordinatorId,
            String claimId,
            Instant claimedAt,
            Duration claimLease) {
        Map<String, Object> metadata = nodeMetadata(run, nodeId, coordinatorId);
        metadata.put(CLAIM_ID, claimId);
        metadata.put(COORDINATOR_ID, coordinatorId);
        metadata.put(CLAIMED_AT, claimedAt.toString());
        metadata.put(EXPIRES_AT, claimedAt.plus(claimLease).toString());
        metadata.put(CLAIM_LEASE, claimLease.toString());
        metadata.put(CLAIM_LEASE_MILLIS, claimLease.toMillis());
        return new CompensationHistoryRecord(COMPENSATION_NODE_CLAIMED, claimId, metadata);
    }

    public static CompensationHistoryRecord nodeClaimReleased(
            WorkflowRun run,
            NodeId nodeId,
            String coordinatorId,
            String claimId,
            Instant releasedAt) {
        Map<String, Object> metadata = nodeMetadata(run, nodeId, coordinatorId);
        metadata.put(CLAIM_ID, claimId);
        metadata.put(COORDINATOR_ID, coordinatorId);
        metadata.put(RELEASED_AT, releasedAt.toString());
        return new CompensationHistoryRecord(COMPENSATION_NODE_CLAIM_RELEASED, claimId, metadata);
    }

    public static CompensationHistoryRecord nodeClaimExpired(
            WorkflowRun run,
            NodeId nodeId,
            String coordinatorId,
            CompensationClaim expiredClaim,
            Instant detectedAt) {
        Map<String, Object> metadata = nodeMetadata(run, nodeId, coordinatorId);
        metadata.put(TAKEOVER_REASON, TAKEOVER_REASON_EXPIRED_CLAIM);
        metadata.put(DETECTED_AT, detectedAt.toString());
        metadata.put(EXPIRED_CLAIM_ID, expiredClaim.claimId());
        metadata.put(EXPIRED_CLAIM_OWNER_ID, claimOwnerId(expiredClaim.claimId()));
        metadata.put(EXPIRED_CLAIMED_AT, expiredClaim.claimedAt().toString());
        metadata.put(EXPIRED_CLAIM_EXPIRES_AT, expiredClaim.expiresAt().toString());
        return new CompensationHistoryRecord(
                COMPENSATION_NODE_CLAIM_EXPIRED,
                expiredClaim.claimId(),
                metadata);
    }

    public static CompensationHistoryRecord nodeClaimSkipped(
            WorkflowRun run,
            NodeId nodeId,
            String coordinatorId,
            CompensationClaim activeClaim,
            Instant skippedAt) {
        Map<String, Object> metadata = nodeMetadata(run, nodeId, coordinatorId);
        metadata.put(SKIP_REASON, SKIP_REASON_ACTIVE_CLAIM);
        metadata.put(SKIPPED_AT, skippedAt.toString());
        metadata.put(ACTIVE_CLAIM_ID, activeClaim.claimId());
        metadata.put(ACTIVE_CLAIM_OWNER_ID, claimOwnerId(activeClaim.claimId()));
        metadata.put(ACTIVE_CLAIMED_AT, activeClaim.claimedAt().toString());
        metadata.put(ACTIVE_CLAIM_EXPIRES_AT, activeClaim.expiresAt().toString());
        return new CompensationHistoryRecord(
                COMPENSATION_NODE_CLAIM_SKIPPED,
                activeClaim.claimId(),
                metadata);
    }

    public static CompensationHistoryRecord nodeFailed(
            WorkflowRun run,
            NodeId nodeId,
            String coordinatorId,
            String claimId,
            CompensationResult nodeResult,
            Instant failedAt) {
        Map<String, Object> metadata = nodeMetadata(run, nodeId, coordinatorId);
        metadata.put(CLAIM_ID, claimId);
        metadata.put(FAILURE_SOURCE, FAILURE_SOURCE_RESULT);
        metadata.put(FAILURE_MESSAGE, failureMessage(nodeResult));
        metadata.put(FAILED_AT, failedAt.toString());
        return new CompensationHistoryRecord(
                COMPENSATION_NODE_FAILED,
                failureMessage(nodeResult),
                metadata);
    }

    public static CompensationHistoryRecord nodeFailed(
            WorkflowRun run,
            NodeId nodeId,
            String coordinatorId,
            String claimId,
            Throwable error,
            Instant failedAt) {
        Map<String, Object> metadata = nodeMetadata(run, nodeId, coordinatorId);
        metadata.put(CLAIM_ID, claimId);
        metadata.put(FAILURE_SOURCE, FAILURE_SOURCE_EXCEPTION);
        metadata.put(FAILURE_MESSAGE, errorMessage(error));
        metadata.put(FAILURE_TYPE, error != null ? error.getClass().getName() : "");
        metadata.put(FAILED_AT, failedAt.toString());
        return new CompensationHistoryRecord(
                COMPENSATION_NODE_FAILED,
                errorMessage(error),
                metadata);
    }

    public static CompensationHistoryRecord nodeSkipped(
            WorkflowRun run,
            NodeId nodeId,
            String coordinatorId,
            String skipReason,
            Instant skippedAt) {
        Map<String, Object> metadata = nodeMetadata(run, nodeId, coordinatorId);
        metadata.put(SKIP_REASON, skipReason);
        metadata.put(SKIPPED_AT, skippedAt.toString());
        return new CompensationHistoryRecord(COMPENSATION_NODE_SKIPPED, skipReason, metadata);
    }

    private static Map<String, Object> completedMetadata(WorkflowRun run, NodeId nodeId) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(STATUS, run.getStatus().name());
        metadata.put(NODE_ID, nodeId.value());
        if (run.getCompensationState() != null) {
            List<String> nodesToCompensate = nodeValues(run.getCompensationState().nodesToCompensate());
            List<String> compensatedNodes = nodeValues(run.getCompensationState().compensatedNodes());
            metadata.put(COMPENSATION_STATUS, run.getCompensationState().status().name());
            metadata.put(NODES_TO_COMPENSATE, nodesToCompensate);
            metadata.put(NODES_TO_COMPENSATE_COUNT, nodesToCompensate.size());
            metadata.put(COMPENSATED_NODES, compensatedNodes);
            metadata.put(COMPENSATED_NODES_COUNT, compensatedNodes.size());
            metadata.put(COMPENSATION_STARTED_AT, run.getCompensationState().startedAt().toString());
        }
        return metadata;
    }

    private static Map<String, Object> nodeMetadata(
            WorkflowRun run,
            NodeId nodeId,
            String coordinatorId) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(STATUS, run.getStatus().name());
        metadata.put(NODE_ID, nodeId.value());
        metadata.put(COORDINATOR_ID, coordinatorId);
        if (run.getCompensationState() != null) {
            List<String> nodesToCompensate = nodeValues(run.getCompensationState().nodesToCompensate());
            List<String> compensatedNodes = nodeValues(run.getCompensationState().compensatedNodes());
            List<String> claimedNodes = nodeValues(run.getCompensationState().compensationClaims().stream()
                    .map(CompensationClaim::nodeId)
                    .toList());
            metadata.put(COMPENSATION_STATUS, run.getCompensationState().status().name());
            metadata.put(NODES_TO_COMPENSATE, nodesToCompensate);
            metadata.put(NODES_TO_COMPENSATE_COUNT, nodesToCompensate.size());
            metadata.put(COMPENSATED_NODES, compensatedNodes);
            metadata.put(COMPENSATED_NODES_COUNT, compensatedNodes.size());
            metadata.put(COMPENSATION_CLAIMS, claimedNodes);
            metadata.put(COMPENSATION_CLAIMS_COUNT, claimedNodes.size());
            metadata.put(COMPENSATION_STARTED_AT, run.getCompensationState().startedAt().toString());
        }
        return metadata;
    }

    private static List<String> nodeValues(List<NodeId> nodes) {
        return nodes != null ? nodes.stream().map(NodeId::value).toList() : List.of();
    }

    private static String claimOwnerId(String claimId) {
        if (claimId == null || claimId.isBlank()) {
            return "";
        }
        int separator = claimId.indexOf(':');
        return separator > 0 ? claimId.substring(0, separator) : claimId;
    }

    private static String failureMessage(CompensationResult result) {
        return result != null && result.message() != null ? result.message() : "unknown failure";
    }

    private static String errorMessage(Throwable error) {
        return error != null && error.getMessage() != null ? error.getMessage() : "unknown failure";
    }
}
