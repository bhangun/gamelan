package tech.kayys.silat.engine;

import java.time.Instant;

import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
class Clock {
    Instant now() {
        return Instant.now();
    }
}
