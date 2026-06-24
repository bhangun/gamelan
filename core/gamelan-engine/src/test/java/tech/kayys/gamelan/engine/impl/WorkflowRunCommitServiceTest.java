package tech.kayys.gamelan.engine.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.smallrye.mutiny.Uni;
import tech.kayys.gamelan.engine.event.ExecutionEvent;
import tech.kayys.gamelan.engine.execution.ExecutionHistoryAppendConflictException;
import tech.kayys.gamelan.engine.execution.ExecutionHistoryRepository;
import tech.kayys.gamelan.engine.node.NodeDefinition;
import tech.kayys.gamelan.engine.node.NodeId;
import tech.kayys.gamelan.engine.node.NodeType;
import tech.kayys.gamelan.engine.repository.WorkflowRunRepository;
import tech.kayys.gamelan.engine.run.RetryPolicy;
import tech.kayys.gamelan.engine.saga.CompensationPolicy;
import tech.kayys.gamelan.engine.tenant.TenantId;
import tech.kayys.gamelan.engine.workflow.WorkflowDefinition;
import tech.kayys.gamelan.engine.workflow.WorkflowDefinitionId;
import tech.kayys.gamelan.engine.workflow.WorkflowMode;
import tech.kayys.gamelan.engine.workflow.WorkflowRun;

class WorkflowRunCommitServiceTest {

    private static final TenantId TENANT = TenantId.of("tenant-1");

    @Test
    void commitEvents_appendsEventsBeforeMarkingRunCommittedAndUpdatingRun() {
        WorkflowRunRepository runRepository = mock(WorkflowRunRepository.class);
        ExecutionHistoryRepository historyRepository = mock(ExecutionHistoryRepository.class);
        WorkflowRunCommitService service = new WorkflowRunCommitService(runRepository, historyRepository);
        WorkflowRun run = newRun();
        List<ExecutionEvent> events = List.copyOf(run.getUncommittedEvents());
        long initialVersion = run.getVersion();

        when(historyRepository.appendEvents(run.getId(), TENANT, events))
                .thenReturn(Uni.createFrom().voidItem());
        when(runRepository.update(run)).thenReturn(Uni.createFrom().item(run));

        service.commitEvents(run, run.getId(), TENANT, events).await().indefinitely();

        assertTrue(run.getUncommittedEvents().isEmpty());
        assertEquals(initialVersion + events.size(), run.getVersion());
        verify(historyRepository).appendEvents(run.getId(), TENANT, events);
        verify(runRepository).update(run);
    }

    @Test
    void commitEvents_noopsWhenThereAreNoEvents() {
        WorkflowRunRepository runRepository = mock(WorkflowRunRepository.class);
        ExecutionHistoryRepository historyRepository = mock(ExecutionHistoryRepository.class);
        WorkflowRunCommitService service = new WorkflowRunCommitService(runRepository, historyRepository);

        service.commitEvents(null, null, null, List.<ExecutionEvent>of()).await().indefinitely();

        verifyNoInteractions(historyRepository, runRepository);
    }

    @Test
    void commitEvents_keepsRunDirtyWhenHistoryAppendFails() {
        WorkflowRunRepository runRepository = mock(WorkflowRunRepository.class);
        ExecutionHistoryRepository historyRepository = mock(ExecutionHistoryRepository.class);
        WorkflowRunCommitService service = new WorkflowRunCommitService(runRepository, historyRepository);
        WorkflowRun run = newRun();
        List<ExecutionEvent> events = List.copyOf(run.getUncommittedEvents());
        long initialVersion = run.getVersion();
        ExecutionHistoryAppendConflictException failure = new ExecutionHistoryAppendConflictException(
                events.getFirst().eventId(),
                run.getId(),
                TENANT);

        when(historyRepository.appendEvents(run.getId(), TENANT, events))
                .thenReturn(Uni.createFrom().failure(failure));

        ExecutionHistoryAppendConflictException thrown = assertThrows(
                ExecutionHistoryAppendConflictException.class,
                () -> service.commitEvents(run, run.getId(), TENANT, events).await().indefinitely());

        assertSame(failure, thrown);
        assertEquals(events.size(), run.getUncommittedEvents().size());
        assertEquals(initialVersion, run.getVersion());
        verify(historyRepository).appendEvents(run.getId(), TENANT, events);
        verify(runRepository, never()).update(any());
    }

    private WorkflowRun newRun() {
        return WorkflowRun.create(TENANT, workflow(), Map.of());
    }

    private WorkflowDefinition workflow() {
        return new WorkflowDefinition(
                WorkflowDefinitionId.of("wf-commit-test"),
                TENANT,
                "commit-test",
                "1.0.0",
                null,
                WorkflowMode.FLOW,
                List.of(node()),
                Map.of(),
                Map.of(),
                null,
                RetryPolicy.none(),
                CompensationPolicy.disabled());
    }

    private NodeDefinition node() {
        return new NodeDefinition(
                NodeId.of("node-1"),
                "node-1",
                NodeType.TASK,
                "local",
                Map.of(),
                List.of(),
                List.of(),
                RetryPolicy.none(),
                Duration.ZERO,
                true);
    }
}
