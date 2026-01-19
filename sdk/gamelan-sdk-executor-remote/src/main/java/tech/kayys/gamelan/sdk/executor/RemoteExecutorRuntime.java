package tech.kayys.gamelan.sdk.executor;

import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.quarkus.runtime.Startup;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import tech.kayys.gamelan.sdk.executor.core.BaseExecutorRuntime;
import tech.kayys.gamelan.sdk.executor.core.WorkflowExecutor;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import jakarta.inject.Inject;

/**
 * Remote executor runtime for distributed execution
 * Supports gRPC and Kafka transports with registration and heartbeat
 */
@Startup
@ApplicationScoped
public class RemoteExecutorRuntime extends BaseExecutorRuntime {

    private static final Logger LOG = LoggerFactory.getLogger(RemoteExecutorRuntime.class);

    private final AtomicBoolean registered = new AtomicBoolean(false);
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    private RemoteExecutorTransport remoteTransport;

    @Inject
    RemoteExecutorTransportFactory transportFactory;

    @Override
    protected RemoteExecutorTransport createTransport() {
        this.remoteTransport = transportFactory.createTransport();
        return remoteTransport;
    }

    @Override
    protected void initialize() {
        super.initialize();

        LOG.info("Initializing Remote Executor Runtime");
        LOG.info("Running in remote mode - will register with engine");
    }

    /**
     * Start the remote runtime with registration
     */
    @PostConstruct
    @Override
    public void start() {
        super.start();

        // Register with remote engine
        registerWithEngine();

        // Start heartbeat
        startHeartbeat();

        LOG.info("Remote Executor Runtime started successfully");
        LOG.info("Transport: {}", remoteTransport.getCommunicationType());
        LOG.info("Registered executors: {}", executors.keySet());
    }

    /**
     * Register all executors with remote engine
     */
    private void registerWithEngine() {
        List<WorkflowExecutor> executorList = List.copyOf(executors.values());

        if (executorList.isEmpty()) {
            LOG.warn("No executors to register");
            return;
        }

        remoteTransport.register(executorList)
                .onItem().invoke(() -> {
                    registered.set(true);
                    LOG.info("Successfully registered {} executors with engine", executorList.size());
                })
                .onFailure().invoke(error -> {
                    LOG.error("Failed to register with engine", error);
                    scheduleRetryRegistration();
                })
                .subscribe().with(
                        v -> LOG.debug("Registration completed"),
                        error -> LOG.error("Registration failed", error));
    }

    /**
     * Schedule retry for registration
     */
    private void scheduleRetryRegistration() {
        scheduler.schedule(() -> {
            if (running && !registered.get()) {
                LOG.info("Retrying registration...");
                registerWithEngine();
            }
        }, 5, TimeUnit.SECONDS);
    }

    /**
     * Start periodic heartbeat
     */
    private void startHeartbeat() {
        Duration heartbeatInterval = remoteTransport.getHeartbeatInterval();

        if (heartbeatInterval == null) {
            LOG.warn("No heartbeat interval configured, skipping heartbeat");
            return;
        }

        scheduler.scheduleAtFixedRate(() -> {
            if (!running || !registered.get()) {
                return;
            }

            remoteTransport.sendHeartbeat()
                    .onFailure().invoke(error -> {
                        LOG.warn("Heartbeat failed, marking as unregistered", error);
                        registered.set(false);

                        // Attempt re-registration
                        if (running) {
                            LOG.info("Attempting re-registration after heartbeat failure");
                            registerWithEngine();
                        }
                    })
                    .subscribe().with(
                            v -> LOG.trace("Heartbeat sent successfully"),
                            error -> {
                            }); // Error already logged
        }, 0, heartbeatInterval.toSeconds(), TimeUnit.SECONDS);

        LOG.info("Heartbeat started with interval: {} seconds", heartbeatInterval.toSeconds());
    }

    /**
     * Stop the remote runtime with cleanup
     */
    @PreDestroy
    @Override
    public void stop() {
        LOG.info("Stopping Remote Executor Runtime");

        // Stop heartbeat scheduler
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }

        // Unregister from engine if registered
        if (registered.get() && remoteTransport != null) {
            remoteTransport.unregister()
                    .onItem().invoke(() -> LOG.info("Successfully unregistered from engine"))
                    .onFailure().invoke(error -> LOG.error("Failed to unregister from engine", error))
                    .await().atMost(Duration.ofSeconds(5));
        }

        super.stop();
    }

    /**
     * Check if registered with engine
     */
    public boolean isRegistered() {
        return registered.get();
    }

    /**
     * Get transport type
     */
    public tech.kayys.gamelan.engine.protocol.CommunicationType getTransportType() {
        return remoteTransport != null ? remoteTransport.getCommunicationType() : null;
    }

    /**
     * Manual re-registration (for recovery scenarios)
     */
    public void reRegister() {
        LOG.info("Manual re-registration requested");
        registered.set(false);
        registerWithEngine();
    }
}