package tech.kayys.gamelan.sdk.client;

public class LocalGamelanClientProvider implements GamelanClientProvider {
    @Override
    public GamelanClient create(GamelanClientConfig config) {
        return new LocalGamelanClient(config);
    }

    @Override
    public boolean supports(GamelanClientConfig config) {
        return config.transport() == TransportType.LOCAL;
    }
}
