package tech.kayys.silat.model;

import java.util.List;

/**
 * Validation Result
 */
public record ValidationResult(boolean isValid, String message, List<String> errors) {
    public static ValidationResult success() {
        return new ValidationResult(true, null, List.of());
    }

    public static ValidationResult failure(String message) {
        return new ValidationResult(false, message, List.of());
    }

    public static ValidationResult failure(String message, List<String> errors) {
        return new ValidationResult(false, message, errors);
    }
}
