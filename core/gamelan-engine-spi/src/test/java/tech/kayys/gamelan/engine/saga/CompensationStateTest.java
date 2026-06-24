package tech.kayys.gamelan.engine.saga;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import tech.kayys.gamelan.engine.error.GamelanException;
import tech.kayys.gamelan.engine.node.NodeId;

class CompensationStateTest {

    @Test
    void markNodeCompensated_whenAlreadyCompensatedKeepsProgressIdempotent() {
        NodeId second = NodeId.of("second");
        NodeId first = NodeId.of("first");
        CompensationState state = CompensationState.create(List.of(second, first));

        CompensationState afterFirstResult = state.markNodeCompensated(second);
        CompensationState afterDuplicateResult = afterFirstResult.markNodeCompensated(second);

        assertEquals(List.of(first), afterDuplicateResult.nodesToCompensate());
        assertEquals(List.of(second), afterDuplicateResult.compensatedNodes());
        assertFalse(afterDuplicateResult.isComplete());
    }

    @Test
    void constructor_defensivelyCopiesNodeLists() {
        NodeId node = NodeId.of("node");
        List<NodeId> nodesToCompensate = new ArrayList<>(List.of(node));
        List<NodeId> compensatedNodes = new ArrayList<>();

        CompensationState state = new CompensationState(
                nodesToCompensate,
                compensatedNodes,
                Instant.now(),
                null,
                CompensationStatus.PENDING);

        nodesToCompensate.clear();
        compensatedNodes.add(NodeId.of("other"));

        assertEquals(List.of(node), state.nodesToCompensate());
        assertEquals(List.of(), state.compensatedNodes());
        assertThrows(UnsupportedOperationException.class,
                () -> state.nodesToCompensate().add(NodeId.of("late")));
    }

    @Test
    void create_deduplicatesPendingNodesPreservingOrder() {
        NodeId second = NodeId.of("second");
        NodeId first = NodeId.of("first");

        CompensationState state = CompensationState.create(List.of(second, first, second, first));

        assertEquals(List.of(second, first), state.nodesToCompensate());
        assertEquals(List.of(), state.compensatedNodes());
    }

    @Test
    void constructor_removesCompensatedOverlapFromPendingNodes() {
        NodeId third = NodeId.of("third");
        NodeId second = NodeId.of("second");
        NodeId first = NodeId.of("first");

        CompensationState state = new CompensationState(
                List.of(third, second, first, second),
                List.of(second, second),
                Instant.now(),
                null,
                CompensationStatus.PENDING);

        assertEquals(List.of(third, first), state.nodesToCompensate());
        assertEquals(List.of(second), state.compensatedNodes());
    }

    @Test
    void claimNode_recordsActiveLeaseWithoutRemovingPendingWork() {
        NodeId node = NodeId.of("node");
        Instant now = Instant.parse("2026-05-27T00:00:00Z");

        CompensationState state = CompensationState.create(List.of(node))
                .claimNode(node, "claim-1", now, Duration.ofMinutes(5));

        assertEquals(List.of(node), state.nodesToCompensate());
        assertEquals(1, state.compensationClaims().size());
        assertEquals("claim-1", state.compensationClaims().getFirst().claimId());
        assertTrue(state.hasActiveClaim(node, now.plusSeconds(60)));
        assertFalse(state.hasActiveClaim(node, now.plus(Duration.ofMinutes(6))));
        assertEquals(CompensationStatus.IN_PROGRESS, state.status());
    }

    @Test
    void releaseNodeClaim_onlyReleasesMatchingClaimToken() {
        NodeId node = NodeId.of("node");
        Instant now = Instant.parse("2026-05-27T00:00:00Z");
        CompensationState claimed = CompensationState.create(List.of(node))
                .claimNode(node, "claim-1", now, Duration.ofMinutes(5));

        CompensationState wrongRelease = claimed.releaseNodeClaim(node, "claim-2");
        CompensationState released = wrongRelease.releaseNodeClaim(node, "claim-1");

        assertEquals(1, wrongRelease.compensationClaims().size());
        assertEquals(List.of(), released.compensationClaims());
        assertEquals(List.of(node), released.nodesToCompensate());
        assertEquals(CompensationStatus.PENDING, released.status());
    }

    @Test
    void markNodeCompensated_clearsClaimForCompletedNode() {
        NodeId node = NodeId.of("node");
        CompensationState state = CompensationState.create(List.of(node))
                .claimNode(node, "claim-1", Instant.now(), Duration.ofMinutes(5))
                .markNodeCompensated(node);

        assertEquals(List.of(), state.compensationClaims());
        assertEquals(List.of(), state.nodesToCompensate());
        assertEquals(List.of(node), state.compensatedNodes());
        assertTrue(state.isComplete());
    }

    @Test
    void create_acceptsNullNodesAsEmptyCompensationList() {
        CompensationState state = CompensationState.create(null);

        assertEquals(List.of(), state.nodesToCompensate());
        assertEquals(List.of(), state.compensatedNodes());
    }

    @Test
    void constructor_normalizesCompletedStateToClosedTerminalState() {
        Instant startedAt = Instant.parse("2026-05-27T00:00:00Z");
        NodeId second = NodeId.of("second");
        NodeId first = NodeId.of("first");

        CompensationState state = new CompensationState(
                List.of(second, first),
                List.of(first),
                startedAt,
                null,
                CompensationStatus.COMPLETED);

        assertEquals(List.of(), state.nodesToCompensate());
        assertEquals(List.of(first, second), state.compensatedNodes());
        assertEquals(startedAt, state.completedAt());
    }

    @Test
    void constructor_normalizesFailedStateToTerminalTimestamp() {
        Instant startedAt = Instant.parse("2026-05-27T00:00:00Z");
        NodeId second = NodeId.of("second");
        NodeId first = NodeId.of("first");

        CompensationState state = new CompensationState(
                List.of(second, first),
                List.of(first),
                startedAt,
                null,
                CompensationStatus.FAILED);

        assertEquals(List.of(second), state.nodesToCompensate());
        assertEquals(List.of(first), state.compensatedNodes());
        assertEquals(startedAt, state.completedAt());
    }

    @Test
    void constructor_clearsStaleCompletedAtForActiveState() {
        Instant startedAt = Instant.parse("2026-05-27T00:00:00Z");
        Instant staleCompletedAt = Instant.parse("2026-05-27T00:01:00Z");

        CompensationState state = new CompensationState(
                List.of(NodeId.of("node")),
                List.of(),
                startedAt,
                staleCompletedAt,
                CompensationStatus.IN_PROGRESS);

        assertNull(state.completedAt());
        assertEquals(CompensationStatus.IN_PROGRESS, state.status());
    }

    @Test
    void markCompleted_whenAlreadyCompletedIsIdempotent() {
        CompensationState completed = CompensationState.create(List.of(NodeId.of("node")))
                .markCompleted();

        assertSame(completed, completed.markCompleted());
    }

    @Test
    void markFailed_whenAlreadyFailedIsIdempotent() {
        CompensationState failed = CompensationState.create(List.of(NodeId.of("node")))
                .markFailed();

        assertSame(failed, failed.markFailed());
    }

    @Test
    void markFailed_rejectsCompletedState() {
        CompensationState completed = CompensationState.create(List.of(NodeId.of("node")))
                .markCompleted();

        GamelanException exception = assertThrows(GamelanException.class, completed::markFailed);

        assertEquals("Cannot fail completed compensation", exception.getMessage());
    }

    @Test
    void markCompleted_rejectsFailedState() {
        CompensationState failed = CompensationState.create(List.of(NodeId.of("node")))
                .markFailed();

        GamelanException exception = assertThrows(GamelanException.class, failed::markCompleted);

        assertEquals("Cannot complete failed compensation", exception.getMessage());
    }

    @Test
    void markNodeCompensated_rejectsFailedStateForPendingNode() {
        NodeId node = NodeId.of("node");
        CompensationState failed = CompensationState.create(List.of(node))
                .markFailed();

        GamelanException exception = assertThrows(GamelanException.class,
                () -> failed.markNodeCompensated(node));

        assertEquals("Cannot mark node compensated after compensation failed", exception.getMessage());
    }

    @Test
    void markNodeCompensated_whenCompletedAndAlreadyCompensatedIsIdempotent() {
        NodeId node = NodeId.of("node");
        CompensationState completed = CompensationState.create(List.of(node))
                .markCompleted();

        assertSame(completed, completed.markNodeCompensated(node));
    }

    @Test
    void markNodeCompensated_rejectsCompletedStateForUnknownNode() {
        CompensationState completed = CompensationState.create(List.of(NodeId.of("node")))
                .markCompleted();

        GamelanException exception = assertThrows(GamelanException.class,
                () -> completed.markNodeCompensated(NodeId.of("late")));

        assertEquals("Cannot mark node compensated after compensation completed", exception.getMessage());
    }
}
