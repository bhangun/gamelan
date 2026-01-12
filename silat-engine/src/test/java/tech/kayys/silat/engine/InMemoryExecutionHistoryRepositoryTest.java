package tech.kayys.silat.engine;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import tech.kayys.silat.execution.ExecutionHistory;
import tech.kayys.silat.model.NodeId;
import tech.kayys.silat.model.WorkflowRunId;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class InMemoryExecutionHistoryRepositoryTest {

        @InjectMocks
        InMemoryExecutionHistoryRepository repository;

        @BeforeEach
        void setUp() {
                // Mocks initialized by MockitoExtension
        }

        @Test
        void append_whenCalled_storesEvent() {
                // Arrange
                WorkflowRunId runId = new WorkflowRunId("run1");
                String type = "TEST_EVENT";
                String message = "Test event message";
                Map<String, Object> metadata = Map.of("key1", "value1", "key2", 123);

                // Act
                repository.append(runId, type, message, metadata)
                                .await().indefinitely();

                // Load the history to verify
                ExecutionHistory history = repository.load(runId)
                                .await().indefinitely();

                // Assert
                assertNotNull(history);
                var events = history.getEvents();
                assertEquals(1, events.size());
        }

        @Test
        void append_multipleEvents_storesAllEvents() {
                // Arrange
                WorkflowRunId runId = new WorkflowRunId("run1");

                // Act
                repository.append(runId, "EVENT1", "Message 1", Map.of())
                                .await().indefinitely();
                repository.append(runId, "EVENT2", "Message 2", Map.of("data", "value"))
                                .await().indefinitely();

                // Load the history to verify
                ExecutionHistory history = repository.load(runId)
                                .await().indefinitely();

                // Assert
                assertNotNull(history);
                var events = history.getEvents();
                assertEquals(2, events.size());
        }

        @Test
        void load_whenNoEvents_returnsEmptyHistory() {
                // Arrange
                WorkflowRunId runId = new WorkflowRunId("nonexistent-run");

                // Act
                ExecutionHistory history = repository.load(runId)
                                .await().indefinitely();

                // Assert
                assertNotNull(history);
                assertTrue(history.getEvents().isEmpty());
        }

        @Test
        void isNodeResultProcessed_whenFirstTime_returnsFalse() {
                // Arrange
                WorkflowRunId runId = new WorkflowRunId("run1");
                NodeId nodeId = new NodeId("node1");
                int attempt = 1;

                // Act
                Boolean isProcessed = repository.isNodeResultProcessed(runId, nodeId, attempt)
                                .await().indefinitely();

                // Assert
                assertFalse(isProcessed);
        }

        @Test
        void isNodeResultProcessed_whenAlreadyProcessed_returnsTrue() {
                // Arrange
                WorkflowRunId runId = new WorkflowRunId("run1");
                NodeId nodeId = new NodeId("node1");
                int attempt = 1;

                // First call - should return false
                Boolean firstCall = repository.isNodeResultProcessed(runId, nodeId, attempt)
                                .await().indefinitely();
                assertFalse(firstCall);

                // Second call - should return true
                Boolean secondCall = repository.isNodeResultProcessed(runId, nodeId, attempt)
                                .await().indefinitely();

                // Assert
                assertTrue(secondCall);
        }

        @Test
        void isNodeResultProcessed_differentAttempts_returnsIndependently() {
                // Arrange
                WorkflowRunId runId = new WorkflowRunId("run1");
                NodeId nodeId = new NodeId("node1");

                // First call with attempt 1 - should return false
                Boolean firstAttempt = repository.isNodeResultProcessed(runId, nodeId, 1)
                                .await().indefinitely();
                assertFalse(firstAttempt);

                // Call with attempt 2 - should return false (different attempt)
                Boolean secondAttempt = repository.isNodeResultProcessed(runId, nodeId, 2)
                                .await().indefinitely();
                assertFalse(secondAttempt);

                // Call again with attempt 1 - should return true
                Boolean firstAttemptAgain = repository.isNodeResultProcessed(runId, nodeId, 1)
                                .await().indefinitely();
                assertTrue(firstAttemptAgain);

                // Call again with attempt 2 - should return true
                Boolean secondAttemptAgain = repository.isNodeResultProcessed(runId, nodeId, 2)
                                .await().indefinitely();
                assertTrue(secondAttemptAgain);
        }

        @Test
        void isNodeResultProcessed_differentRuns_returnsIndependently() {
                // Arrange
                WorkflowRunId runId1 = new WorkflowRunId("run1");
                WorkflowRunId runId2 = new WorkflowRunId("run2");
                NodeId nodeId = new NodeId("node1");
                int attempt = 1;

                // First call with run1 - should return false
                Boolean run1First = repository.isNodeResultProcessed(runId1, nodeId, attempt)
                                .await().indefinitely();
                assertFalse(run1First);

                // Call with run2 - should return false (different run)
                Boolean run2First = repository.isNodeResultProcessed(runId2, nodeId, attempt)
                                .await().indefinitely();
                assertFalse(run2First);

                // Call again with run1 - should return true
                Boolean run1Second = repository.isNodeResultProcessed(runId1, nodeId, attempt)
                                .await().indefinitely();
                assertTrue(run1Second);

                // Call again with run2 - should return true
                Boolean run2Second = repository.isNodeResultProcessed(runId2, nodeId, attempt)
                                .await().indefinitely();
                assertTrue(run2Second);
        }
}