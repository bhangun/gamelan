package tech.kayys.gamelan.engine.execution;

/**
 * Stable at-rest representation for bearer execution tokens.
 */
public final class ExecutionTokenHash {

    private ExecutionTokenHash() {
    }

    public static String sha256(String tokenValue) {
        return BearerTokenHash.sha256(tokenValue);
    }
}
