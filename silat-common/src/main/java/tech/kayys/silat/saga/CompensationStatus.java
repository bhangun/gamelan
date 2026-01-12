package tech.kayys.silat.saga;

/**
 * Compensation Status - Represents the status of compensation process
 */
public enum CompensationStatus {
    PENDING,      // Compensation has been initiated but not started
    IN_PROGRESS,  // Compensation is currently in progress
    COMPLETED,    // All compensations have been completed successfully
    FAILED        // Compensation process has failed
}