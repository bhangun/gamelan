package tech.kayys.gamelan.engine;

import java.time.Clock;
import java.time.Instant;

import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class SystemClock {
    private final Clock delegate = Clock.systemUTC();

    public Instant now() {
        return delegate.instant();
    }

    // Method to get the underlying Java Clock for compatibility
    public Clock asJavaClock() {
        return delegate;
    }
}
