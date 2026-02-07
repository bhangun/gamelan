package tech.kayys.gamelan.engine.tenant;

import java.util.Objects;
import tech.kayys.gamelan.engine.error.ErrorCode;
import tech.kayys.gamelan.engine.error.GamelanException;

/**
 * Tenant Identifier for multi-tenancy support
 */
public record TenantId(String value) {
    public TenantId {
        Objects.requireNonNull(value, "TenantId value cannot be null");
        if (value.isBlank()) {
            throw new GamelanException(ErrorCode.VALIDATION_FAILED, "TenantId cannot be blank");
        }
    }

    @com.fasterxml.jackson.annotation.JsonValue
    public String value() {
        return value;
    }

    @com.fasterxml.jackson.annotation.JsonCreator
    public static TenantId of(String value) {
        return new TenantId(value);
    }

    public static TenantId system() {
        return new TenantId("system");
    }

    @Override
    public String toString() {
        return value;
    }
}
