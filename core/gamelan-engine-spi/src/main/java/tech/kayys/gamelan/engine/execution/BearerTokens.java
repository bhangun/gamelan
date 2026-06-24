package tech.kayys.gamelan.engine.execution;

import java.security.SecureRandom;
import java.util.Base64;

import tech.kayys.gamelan.engine.error.ErrorCode;
import tech.kayys.gamelan.engine.error.GamelanException;

/**
 * Secure URL-safe bearer-token generation for execution and callback secrets.
 */
public final class BearerTokens {

    public static final int DEFAULT_BYTES = 32;

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final int MIN_BYTES = 16;

    private BearerTokens() {
    }

    public static String randomUrlSafe() {
        return randomUrlSafe(DEFAULT_BYTES);
    }

    public static String randomUrlSafe(int bytes) {
        if (bytes < MIN_BYTES) {
            throw new GamelanException(
                    ErrorCode.VALIDATION_FAILED,
                    "Bearer token entropy must be at least " + MIN_BYTES + " bytes");
        }

        byte[] tokenBytes = new byte[bytes];
        SECURE_RANDOM.nextBytes(tokenBytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);
    }
}
