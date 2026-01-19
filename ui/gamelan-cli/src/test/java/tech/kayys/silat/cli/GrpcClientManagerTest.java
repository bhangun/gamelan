package tech.kayys.gamelan.cli;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class GrpcClientManagerTest {

    @Test
    void testGrpcClientManagerCreation() {
        // Test that the client manager can be instantiated
        assertDoesNotThrow(() -> {
            GrpcClientManager manager = new GrpcClientManager("localhost:9090");
            manager.close(); // Clean up resources
        });
    }

    @Test
    void testGrpcClientManagerWithInvalidAddress() {
        // Test that the client manager handles invalid addresses gracefully
        assertDoesNotThrow(() -> {
            GrpcClientManager manager = new GrpcClientManager("invalid-address:9999");
            manager.close(); // Clean up resources
        });
    }
}