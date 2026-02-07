package tech.kayys.gamelan.sdk.client;

public class RemoteGamelanClientProvider implements GamelanClientProvider {
    @Override
    public GamelanClient create(GamelanClientConfig config) {
        return new RemoteGamelanClient(config);
    }

    @Override
    public boolean supports(GamelanClientConfig config) {
        return config.transport() == TransportType.REST || config.transport() == TransportType.GRPC;
    }
}
