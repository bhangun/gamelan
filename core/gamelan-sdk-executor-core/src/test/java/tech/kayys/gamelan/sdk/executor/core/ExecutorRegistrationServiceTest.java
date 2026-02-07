package tech.kayys.gamelan.sdk.executor.core;

import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.core.Vertx;
import io.vertx.mutiny.core.eventbus.EventBus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import tech.kayys.gamelan.engine.protocol.CommunicationType;

import java.util.Collections;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

public class ExecutorRegistrationServiceTest {

    @Mock
    Vertx vertx;

    @Mock
    EventBus eventBus;

    ExecutorRegistrationService service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        when(vertx.eventBus()).thenReturn(eventBus);
        io.vertx.mutiny.core.eventbus.Message<Object> mockMessage = mock(io.vertx.mutiny.core.eventbus.Message.class);
        when(mockMessage.body()).thenReturn("success");
        when(eventBus.request(anyString(), any())).thenReturn(Uni.createFrom().item(mockMessage));
        when(vertx.setPeriodic(anyLong(), any())).thenReturn(1L);

        service = new ExecutorRegistrationService();
        service.vertx = vertx;
    }

    @Test
    void testRegister() {
        ExecutorConfig config = new ExecutorConfig(10, Collections.emptyList(), CommunicationType.GRPC, null);
        service.register(config, "test-type", "test-id").await().indefinitely();

        verify(eventBus).request(eq("gamelan.executor.register"), any());
        verify(vertx).setPeriodic(anyLong(), any());
    }

    @Test
    void testUnregister() {
        // First register to set registered flag
        ExecutorConfig config = new ExecutorConfig(10, Collections.emptyList(), CommunicationType.GRPC, null);
        service.register(config, "test-type", "test-id").await().indefinitely();

        // Then unregister
        service.unregister("test-id").await().indefinitely();

        verify(eventBus).request(eq("gamelan.executor.unregister"), eq("test-id"));
        verify(vertx).cancelTimer(anyLong());
    }
}
