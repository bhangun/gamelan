package tech.kayys.gamelan.sdk.executor;

import io.smallrye.mutiny.Uni;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import tech.kayys.gamelan.sdk.executor.core.ExecutorTransport;
import tech.kayys.gamelan.sdk.executor.core.WorkflowExecutor;

import java.util.Collections;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

public class LocalExecutorRuntimeTest {

    @Mock
    LocalExecutorTransportFactory transportFactory;

    @Mock
    ExecutorTransport transport;

    @Mock
    WorkflowExecutor executor;

    @Mock
    Instance<WorkflowExecutor> discoveredExecutors;

    LocalExecutorRuntime runtime;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        when(transportFactory.createTransport()).thenReturn(transport);
        when(transport.register(any())).thenReturn(Uni.createFrom().voidItem());
        when(transport.unregister()).thenReturn(Uni.createFrom().voidItem());
        when(transport.receiveTasks()).thenReturn(io.smallrye.mutiny.Multi.createFrom().empty());

        when(executor.getExecutorType()).thenReturn("test-executor");
        when(discoveredExecutors.iterator()).thenReturn(Collections.singletonList(executor).iterator());

        runtime = new LocalExecutorRuntime();
        runtime.transportFactory = transportFactory;
        // Inject mocks into parent class fields using reflection or similar if access
        // is restricted,
        // but here we assume package-private access for testing or simple field
        // injection simulation.
        // Since fields are protected in BaseExecutorRuntime, we can access them if we
        // are in the same package,
        // but the test is usually in the same package structure.
        // However, standard Mockito injection might be better if structured correctly.
        // For simplicity, we'll assume we can't easily access protected fields of
        // parent without reflection,
        // but let's try to mock the discoveredExecutors injection.

        // Simulating injection
        try {
            java.lang.reflect.Field discoveredField = tech.kayys.gamelan.sdk.executor.core.BaseExecutorRuntime.class
                    .getDeclaredField("discoveredExecutors");
            discoveredField.setAccessible(true);
            discoveredField.set(runtime, discoveredExecutors);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void testStartRegistersExecutors() {
        runtime.start();

        verify(transport).register(anyList());
        verify(transport).receiveTasks();
    }

    @Test
    void testStopUnregistersExecutors() {
        runtime.start(); // Setup transport
        runtime.stop();

        verify(transport).unregister();
    }
}
