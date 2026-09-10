package tech.kayys.gamelan.sdk.executor.core;

/**
 * Security configuration for the executor
 */
public record SecurityConfig(
        boolean mtlsEnabled,
        boolean jwtEnabled,
        String keyCertChainPath,
        String privateKeyPath,
        String trustCertCollectionPath,
        String jwtToken) {

    public static SecurityConfig disabled() {
        return builder()
                .mtlsEnabled(false)
                .jwtEnabled(false)
                .build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private boolean mtlsEnabled;
        private boolean jwtEnabled;
        private String keyCertChainPath;
        private String privateKeyPath;
        private String trustCertCollectionPath;
        private String jwtToken;

        public Builder mtlsEnabled(boolean mtlsEnabled) {
            this.mtlsEnabled = mtlsEnabled;
            return this;
        }

        public Builder jwtEnabled(boolean jwtEnabled) {
            this.jwtEnabled = jwtEnabled;
            return this;
        }

        public Builder keyCertChainPath(String keyCertChainPath) {
            this.keyCertChainPath = keyCertChainPath;
            return this;
        }

        public Builder privateKeyPath(String privateKeyPath) {
            this.privateKeyPath = privateKeyPath;
            return this;
        }

        public Builder trustCertCollectionPath(String trustCertCollectionPath) {
            this.trustCertCollectionPath = trustCertCollectionPath;
            return this;
        }

        public Builder jwtToken(String jwtToken) {
            this.jwtToken = jwtToken;
            return this;
        }

        public SecurityConfig build() {
            return new SecurityConfig(mtlsEnabled, jwtEnabled, keyCertChainPath, privateKeyPath,
                    trustCertCollectionPath, jwtToken);
        }
    }
}
