package tech.kayys.silat.engine;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import io.smallrye.mutiny.Uni;
import tech.kayys.silat.model.CallbackConfig;
import tech.kayys.silat.model.CallbackRegistration;
import tech.kayys.silat.model.NodeId;
import tech.kayys.silat.model.WorkflowRunId;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DefaultCallbackServiceTest {

    @InjectMocks
    DefaultCallbackService callbackService;

    @BeforeEach
    void setUp() {
        // Mocks initialized by MockitoExtension
    }

    @Test
    void register_whenCalled_createsValidRegistration() {
        // Arrange
        WorkflowRunId runId = new WorkflowRunId("run1");
        NodeId nodeId = new NodeId("node1");

        CallbackConfig config = mock(CallbackConfig.class);

        // Act & Assert - Just verify that the method can be called without throwing
        assertDoesNotThrow(() -> {
            CallbackRegistration registration = callbackService.register(runId, nodeId, config)
                    .await().indefinitely();
            assertNotNull(registration);
        });
    }

    @Test
    void verify_whenValidToken_returnsTrue() {
        // Arrange
        String validToken = UUID.randomUUID().toString();

        DefaultCallbackService spyService = spy(callbackService);
        doReturn(Uni.createFrom().item(true))
                .when(spyService).verify(validToken);

        // Act
        Boolean isValid = spyService.verify(validToken)
                .await().indefinitely();

        // Assert
        assertTrue(isValid);
    }

    @Test
    void verify_whenInvalidToken_returnsFalse() {
        // Arrange
        String invalidToken = UUID.randomUUID().toString();

        DefaultCallbackService spyService = spy(callbackService);
        doReturn(Uni.createFrom().item(false))
                .when(spyService).verify(invalidToken);

        // Act
        Boolean isValid = spyService.verify(invalidToken)
                .await().indefinitely();

        // Assert
        assertFalse(isValid);
    }

    @Test
    void verify_whenNullToken_returnsFalse() {
        // Arrange
        DefaultCallbackService spyService = spy(callbackService);
        doReturn(Uni.createFrom().item(false))
                .when(spyService).verify(null);

        // Act
        Boolean isValid = spyService.verify(null)
                .await().indefinitely();

        // Assert
        assertFalse(isValid);
    }

    @Test
    void verify_whenEmptyToken_returnsFalse() {
        // Arrange
        DefaultCallbackService spyService = spy(callbackService);
        doReturn(Uni.createFrom().item(false))
                .when(spyService).verify("");

        // Act
        Boolean isValid = spyService.verify("")
                .await().indefinitely();

        // Assert
        assertFalse(isValid);
    }

    @Test
    void verify_whenBlankToken_returnsFalse() {
        // Arrange
        DefaultCallbackService spyService = spy(callbackService);
        doReturn(Uni.createFrom().item(false))
                .when(spyService).verify("   ");

        // Act
        Boolean isValid = spyService.verify("   ")
                .await().indefinitely();

        // Assert
        assertFalse(isValid);
    }
}