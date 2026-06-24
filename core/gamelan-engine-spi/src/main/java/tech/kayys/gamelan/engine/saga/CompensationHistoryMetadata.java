package tech.kayys.gamelan.engine.saga;

import java.util.List;

/**
 * Canonical metadata keys and stable values used by compensation history events.
 */
public final class CompensationHistoryMetadata {
    public static final String STATUS = "status";
    public static final String NODE_ID = "nodeId";
    public static final String COMPENSATION_STATUS = "compensationStatus";
    public static final String NODES_TO_COMPENSATE = "nodesToCompensate";
    public static final String NODES_TO_COMPENSATE_COUNT = "nodesToCompensateCount";
    public static final String COMPENSATED_NODES = "compensatedNodes";
    public static final String COMPENSATED_NODES_COUNT = "compensatedNodesCount";
    public static final String COMPENSATION_CLAIMS = "compensationClaims";
    public static final String COMPENSATION_CLAIMS_COUNT = "compensationClaimsCount";
    public static final String COMPENSATION_STARTED_AT = "compensationStartedAt";
    public static final String CLAIM_ID = "claimId";
    public static final String COORDINATOR_ID = "coordinatorId";
    public static final String CLAIMED_AT = "claimedAt";
    public static final String EXPIRES_AT = "expiresAt";
    public static final String CLAIM_LEASE = "claimLease";
    public static final String CLAIM_LEASE_MILLIS = "claimLeaseMillis";
    public static final String RELEASED_AT = "releasedAt";
    public static final String TAKEOVER_REASON = "takeoverReason";
    public static final String DETECTED_AT = "detectedAt";
    public static final String EXPIRED_CLAIM_ID = "expiredClaimId";
    public static final String EXPIRED_CLAIM_OWNER_ID = "expiredClaimOwnerId";
    public static final String EXPIRED_CLAIMED_AT = "expiredClaimedAt";
    public static final String EXPIRED_CLAIM_EXPIRES_AT = "expiredClaimExpiresAt";
    public static final String SKIP_REASON = "skipReason";
    public static final String SKIPPED_AT = "skippedAt";
    public static final String ACTIVE_CLAIM_ID = "activeClaimId";
    public static final String ACTIVE_CLAIM_OWNER_ID = "activeClaimOwnerId";
    public static final String ACTIVE_CLAIMED_AT = "activeClaimedAt";
    public static final String ACTIVE_CLAIM_EXPIRES_AT = "activeClaimExpiresAt";
    public static final String FAILURE_SOURCE = "failureSource";
    public static final String FAILURE_MESSAGE = "failureMessage";
    public static final String FAILURE_TYPE = "failureType";
    public static final String FAILED_AT = "failedAt";

    public static final String TAKEOVER_REASON_EXPIRED_CLAIM = "expired-claim";
    public static final String SKIP_REASON_ACTIVE_CLAIM = "active-claim";
    public static final String SKIP_REASON_ALREADY_COMPENSATED = "already-compensated";
    public static final String FAILURE_SOURCE_RESULT = "result";
    public static final String FAILURE_SOURCE_EXCEPTION = "exception";

    private static final List<String> KEYS = List.of(
            STATUS,
            NODE_ID,
            COMPENSATION_STATUS,
            NODES_TO_COMPENSATE,
            NODES_TO_COMPENSATE_COUNT,
            COMPENSATED_NODES,
            COMPENSATED_NODES_COUNT,
            COMPENSATION_CLAIMS,
            COMPENSATION_CLAIMS_COUNT,
            COMPENSATION_STARTED_AT,
            CLAIM_ID,
            COORDINATOR_ID,
            CLAIMED_AT,
            EXPIRES_AT,
            CLAIM_LEASE,
            CLAIM_LEASE_MILLIS,
            RELEASED_AT,
            TAKEOVER_REASON,
            DETECTED_AT,
            EXPIRED_CLAIM_ID,
            EXPIRED_CLAIM_OWNER_ID,
            EXPIRED_CLAIMED_AT,
            EXPIRED_CLAIM_EXPIRES_AT,
            SKIP_REASON,
            SKIPPED_AT,
            ACTIVE_CLAIM_ID,
            ACTIVE_CLAIM_OWNER_ID,
            ACTIVE_CLAIMED_AT,
            ACTIVE_CLAIM_EXPIRES_AT,
            FAILURE_SOURCE,
            FAILURE_MESSAGE,
            FAILURE_TYPE,
            FAILED_AT);

    private CompensationHistoryMetadata() {
    }

    public static List<String> keys() {
        return KEYS;
    }
}
