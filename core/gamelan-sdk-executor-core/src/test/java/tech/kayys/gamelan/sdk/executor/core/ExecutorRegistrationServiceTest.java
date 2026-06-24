package tech.kayys.gamelan.sdk.executor.core;

import io.vertx.mutiny.core.Vertx;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tech.kayys.gamelan.engine.executor.ExecutorPlacementRequirements;
import tech.kayys.gamelan.engine.protocol.CommunicationType;

import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class ExecutorRegistrationServiceTest {

    Vertx vertx;
    ExecutorRegistrationService service;

    @BeforeEach
    void setUp() {
        vertx = Vertx.vertx();
        service = new ExecutorRegistrationService();
        service.vertx = vertx;
    }

    @AfterEach
    void tearDown() {
        vertx.close().await().indefinitely();
    }

    @Test
    void testRegister() {
        AtomicInteger registrations = new AtomicInteger();
        AtomicReference<JsonObject> registration = new AtomicReference<>();
        vertx.eventBus().consumer("gamelan.executor.register", message -> {
            registrations.incrementAndGet();
            registration.set((JsonObject) message.body());
            message.reply("success");
        });

        ExecutorConfig config = new ExecutorConfig(10, Collections.emptyList(), CommunicationType.GRPC, null);
        service.register(config, "test-type", "test-id").await().indefinitely();

        assertEquals(1, registrations.get());
        JsonObject metadata = registration.get().getJsonObject("metadata");
        assertEquals("remote,distributed", metadata.getString(ExecutorPlacementRequirements.METADATA_RUNTIMES_KEY));
    }

    @Test
    void testUnregister() {
        AtomicInteger registrations = new AtomicInteger();
        AtomicInteger unregistrations = new AtomicInteger();
        vertx.eventBus().consumer("gamelan.executor.register", message -> {
            registrations.incrementAndGet();
            message.reply("success");
        });
        vertx.eventBus().consumer("gamelan.executor.unregister", message -> {
            unregistrations.incrementAndGet();
            message.reply("success");
        });

        ExecutorConfig config = new ExecutorConfig(10, Collections.emptyList(), CommunicationType.GRPC, null);
        service.register(config, "test-type", "test-id").await().indefinitely();
        service.unregister("test-id").await().indefinitely();

        assertEquals(1, registrations.get());
        assertEquals(1, unregistrations.get());
    }
}
