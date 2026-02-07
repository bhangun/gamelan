package tech.kayys.gamelan.sdk.client;

import java.util.ServiceLoader;

/**
 * Interface for SDK transport providers.
 */
public interface GamelanClientProvider {
    /**
     * @param config the client configuration
     * @return a client if this provider can handle the config, null otherwise
     */
    GamelanClient create(GamelanClientConfig config);

    /**
     * @param config the client configuration
     * @return true if this provider supports the transport type in the config
     */
    boolean supports(GamelanClientConfig config);

    /**
     * Finds the first available provider that supports the given config.
     */
    static GamelanClient findAndCreate(GamelanClientConfig config) {
        ServiceLoader<GamelanClientProvider> loader = ServiceLoader.load(GamelanClientProvider.class);
        for (GamelanClientProvider provider : loader) {
            if (provider.supports(config)) {
                return provider.create(config);
            }
        }
        throw new GamelanClientException("No provider found for transport: " + config.transport());
    }
}
