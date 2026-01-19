package tech.kayys.gamelan.runtime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import jakarta.enterprise.inject.Instance;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.stream.Stream;

class ExecutorAdapterRegistryTest {

    @Mock
    private Instance<ExecutorAdapter> adapterInstances;

    @Mock
    private ExecutorAdapter mockAdapter;

    private ExecutorAdapterRegistry registry;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);

        when(mockAdapter.getExecutorType()).thenReturn("test");
        when(adapterInstances.stream()).thenReturn(Stream.of(mockAdapter));

        registry = new ExecutorAdapterRegistry(adapterInstances);
    }

    @Test
    void testRegisterAdapter() {
        assertTrue(registry.hasAdapter("test"));
        assertEquals(mockAdapter, registry.getAdapter("test"));
    }

    @Test
    void testGetAdapterThrowsWhenNotFound() {
        assertThrows(IllegalArgumentException.class, () -> {
            registry.getAdapter("nonexistent");
        });
    }

    @Test
    void testGetAllAdapters() {
        assertEquals(1, registry.getAllAdapters().size());
        assertTrue(registry.getAllAdapters().containsKey("test"));
    }
}
