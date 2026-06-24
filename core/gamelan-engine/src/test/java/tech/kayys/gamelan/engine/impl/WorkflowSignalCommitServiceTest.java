package tech.kayys.gamelan.engine.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import io.smallrye.mutiny.Uni;
import tech.kayys.gamelan.engine.execution.ExecutionHistoryRepository;
import tech.kayys.gamelan.engine.node.NodeDefinition;
import tech.kayys.gamelan.engine.node.NodeId;
import tech.kayys.gamelan.engine.node.NodeType;
import tech.kayys.gamelan.engine.repository.WorkflowRunRepository;
import tech.kayys.gamelan.engine.run.RetryPolicy;
import tech.kayys.gamelan.engine.run.RunStatus;
import tech.kayys.gamelan.engine.saga.CompensationPolicy;
import tech.kayys.gamelan.engine.signal.Signal;
import tech.kayys.gamelan.engine.tenant.TenantId;
import tech.kayys.gamelan.engine.workflow.WorkflowDefinition;
import tech.kayys.gamelan.engine.workflow.WorkflowDefinitionId;
import tech.kayys.gamelan.engine.workflow.WorkflowMode;
import tech.kayys.gamelan.engine.workflow.WorkflowRun;

class WorkflowSignalCommitServiceTest {

    private static final TenantId TENANT = TenantId.of("tenant-1");
    private static final NodeId NODE_ID = NodeId.of("node-1");

    @Test
    void commitAcceptedSignal_appendsAuditBeforeUpdatingRunAndMarkingProcessed() {
        WorkflowRunRepository runRepository = mock(WorkflowRunRepository.class);
        ExecutionHistoryRepository historyRepository = mock(ExecutionHistoryRepository.class);
        WorkflowSignalCommitService service = new WorkflowSignalCommitService(runRepository, historyRepository);
        WorkflowRun run = suspendedRun();
        Signal signal = signal();
        Map<String, Object> metadata = metadata();
        String idempotencyKey = "signal-1";

        when(historyRepository.appendSignalReceivedAudit(
                run.getId(),
                TENANT,
                idempotencyKey,
                signal.name(),
                metadata)).thenReturn(Uni.createFrom().item(true));
        when(runRepository.update(run)).thenReturn(Uni.createFrom().item(run));
        when(historyRepository.markExternalSignalProcessed(run.getId(), TENANT, idempotencyKey))
                .thenReturn(Uni.createFrom().item(true));

        service.commitAcceptedSignal(run, run.getId(), TENANT, signal, idempotencyKey, metadata)
                .await().indefinitely();

        assertEquals(RunStatus.RUNNING, run.getStatus());
        assertEquals("yes", run.getContext().getVariable("approval.result"));
        InOrder inOrder = inOrder(historyRepository, runRepository);
        inOrder.verify(historyRepository).appendSignalReceivedAudit(
                run.getId(),
                TENANT,
                idempotencyKey,
                signal.name(),
                metadata);
        inOrder.verify(runRepository).update(run);
        inOrder.verify(historyRepository).markExternalSignalProcessed(run.getId(), TENANT, idempotencyKey);
    }

    @Test
    void commitAcceptedSignal_doesNotMutateRunWhenAuditAppendFails() {
        WorkflowRunRepository runRepository = mock(WorkflowRunRepository.class);
        ExecutionHistoryRepository historyRepository = mock(ExecutionHistoryRepository.class);
        WorkflowSignalCommitService service = new WorkflowSignalCommitService(runRepository, historyRepository);
        WorkflowRun run = suspendedRun();
        Signal signal = signal();
        Map<String, Object> metadata = metadata();
        String idempotencyKey = "signal-1";
        RuntimeException failure = new RuntimeException("audit unavailable");

        when(historyRepository.appendSignalReceivedAudit(
                run.getId(),
                TENANT,
                idempotencyKey,
                signal.name(),
                metadata)).thenReturn(Uni.createFrom().failure(failure));

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> service.commitAcceptedSignal(run, run.getId(), TENANT, signal, idempotencyKey, metadata)
                        .await().indefinitely());

        assertSame(failure, thrown);
        assertEquals(RunStatus.SUSPENDED, run.getStatus());
        assertNull(run.getContext().getVariable("approval.result"));
        verify(runRepository, never()).update(any());
        verify(historyRepository, never()).markExternalSignalProcessed(any(), any(), any());
    }

    @Test
    void commitAcceptedSignal_completesWhenAuditAlreadyExists() {
        WorkflowRunRepository runRepository = mock(WorkflowRunRepository.class);
        ExecutionHistoryRepository historyRepository = mock(ExecutionHistoryRepository.class);
        WorkflowSignalCommitService service = new WorkflowSignalCommitService(runRepository, historyRepository);
        WorkflowRun run = suspendedRun();
        Signal signal = signal();
        Map<String, Object> metadata = metadata();
        String idempotencyKey = "signal-1";

        when(historyRepository.appendSignalReceivedAudit(
                run.getId(),
                TENANT,
                idempotencyKey,
                signal.name(),
                metadata)).thenReturn(Uni.createFrom().item(false));
        when(runRepository.update(run)).thenReturn(Uni.createFrom().item(run));
        when(historyRepository.markExternalSignalProcessed(run.getId(), TENANT, idempotencyKey))
                .thenReturn(Uni.createFrom().item(true));

        service.commitAcceptedSignal(run, run.getId(), TENANT, signal, idempotencyKey, metadata)
                .await().indefinitely();

        assertEquals(RunStatus.RUNNING, run.getStatus());
        assertEquals("yes", run.getContext().getVariable("approval.result"));
        verify(runRepository).update(run);
        verify(historyRepository).markExternalSignalProcessed(run.getId(), TENANT, idempotencyKey);
    }

    @Test
    void commitAcceptedSignal_doesNotRebufferAlreadyAppliedSignalWhenMarkerRetryRuns() {
        WorkflowRunRepository runRepository = mock(WorkflowRunRepository.class);
        ExecutionHistoryRepository historyRepository = mock(ExecutionHistoryRepository.class);
        WorkflowSignalCommitService service = new WorkflowSignalCommitService(runRepository, historyRepository);
        WorkflowRun run = suspendedRun();
        Signal signal = signal();
        run.signal(signal);
        run.markEventsAsCommitted();
        Map<String, Object> metadata = metadata();
        String idempotencyKey = "signal-1";

        when(historyRepository.appendSignalReceivedAudit(
                run.getId(),
                TENANT,
                idempotencyKey,
                signal.name(),
                metadata)).thenReturn(Uni.createFrom().item(false));
        when(historyRepository.markExternalSignalProcessed(run.getId(), TENANT, idempotencyKey))
                .thenReturn(Uni.createFrom().item(true));

        service.commitAcceptedSignal(run, run.getId(), TENANT, signal, idempotencyKey, metadata)
                .await().indefinitely();

        assertEquals(RunStatus.RUNNING, run.getStatus());
        assertEquals("yes", run.getContext().getVariable("approval.result"));
        assertEquals(0, run.createSnapshot().pendingSignals().size());
        verify(runRepository, never()).update(any());
        verify(historyRepository).markExternalSignalProcessed(run.getId(), TENANT, idempotencyKey);
    }

    @Test
    void commitAcceptedSignal_doesNotMarkProcessedWhenRunUpdateFails() {
        WorkflowRunRepository runRepository = mock(WorkflowRunRepository.class);
        ExecutionHistoryRepository historyRepository = mock(ExecutionHistoryRepository.class);
        WorkflowSignalCommitService service = new WorkflowSignalCommitService(runRepository, historyRepository);
        WorkflowRun run = suspendedRun();
        Signal signal = signal();
        Map<String, Object> metadata = metadata();
        String idempotencyKey = "signal-1";
        RuntimeException failure = new RuntimeException("run store unavailable");

        when(historyRepository.appendSignalReceivedAudit(
                run.getId(),
                TENANT,
                idempotencyKey,
                signal.name(),
                metadata)).thenReturn(Uni.createFrom().item(true));
        when(runRepository.update(run)).thenReturn(Uni.createFrom().failure(failure));

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> service.commitAcceptedSignal(run, run.getId(), TENANT, signal, idempotencyKey, metadata)
                        .await().indefinitely());

        assertSame(failure, thrown);
        verify(historyRepository).appendSignalReceivedAudit(
                run.getId(),
                TENANT,
                idempotencyKey,
                signal.name(),
                metadata);
        verify(runRepository).update(run);
        verify(historyRepository, never()).markExternalSignalProcessed(any(), any(), any());
    }

    private WorkflowRun suspendedRun() {
        WorkflowRun run = WorkflowRun.create(TENANT, workflow(), Map.of());
        run.start();
        run.suspend("waiting for approval", NODE_ID);
        run.markEventsAsCommitted();
        return run;
    }

    private Signal signal() {
        return new Signal(
                "approved",
                NODE_ID,
                Map.of("approval.result", "yes"),
                Instant.parse("2026-05-26T01:02:03Z"));
    }

    private Map<String, Object> metadata() {
        return Map.of(
                "targetNodeId", NODE_ID.value(),
                "idempotencyKey", "signal-1");
    }

    private WorkflowDefinition workflow() {
        return new WorkflowDefinition(
                WorkflowDefinitionId.of("wf-signal-test"),
                TENANT,
                "signal-test",
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
                NODE_ID,
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
