package tech.kayys.gamelan.engine.execution;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Objects;

/**
 * Stable at-rest representation for bearer-style secrets.
 */
public final class BearerTokenHash {

    private BearerTokenHash() {
    }

    public static String sha256(String tokenValue) {
        Objects.requireNonNull(tokenValue, "tokenValue cannot be null");
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(tokenValue.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is not available", error);
        }
    }
}
