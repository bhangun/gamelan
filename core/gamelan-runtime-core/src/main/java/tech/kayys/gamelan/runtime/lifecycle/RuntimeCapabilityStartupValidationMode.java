package tech.kayys.gamelan.runtime.lifecycle;

import java.util.Locale;

public enum RuntimeCapabilityStartupValidationMode {
    DISABLED,
    WARN,
    FAIL;

    static RuntimeCapabilityStartupValidationMode from(String value) {
        if (value == null || value.isBlank()) {
            return WARN;
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "disabled", "off", "false", "none" -> DISABLED;
            case "warn", "warning" -> WARN;
            case "fail", "strict", "error" -> FAIL;
            default -> throw new IllegalArgumentException(
                    "Unsupported gamelan.runtime.capabilities.startup-validation.mode: " + value);
        };
    }
}
