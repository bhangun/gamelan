package tech.kayys.gamelan.sdk.executor;

import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import org.junit.jupiter.api.Test;
import tech.kayys.gamelan.engine.node.NodeExecutionResult;
import tech.kayys.gamelan.engine.node.NodeExecutionTask;
import tech.kayys.gamelan.engine.protocol.CommunicationType;
import tech.kayys.gamelan.sdk.executor.core.WorkflowExecutor;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RemoteExecutorTransportFactoryTest {

    @Test
    void selectsGrpcTransportByDefaultName() {
        RemoteExecutorTransportFactory factory = factory(
                new RecordingRemoteTransport(CommunicationType.GRPC),
                new RecordingRemoteTransport(CommunicationType.KAFKA),
                "GRPC");

        assertSame(factory.grpcTransport, factory.createTransport());
    }

    @Test
    void selectsKafkaTransportCaseInsensitively() {
        RemoteExecutorTransportFactory factory = factory(
                new RecordingRemoteTransport(CommunicationType.GRPC),
                new RecordingRemoteTransport(CommunicationType.KAFKA),
                "kafka");

        assertSame(factory.kafkaTransport, factory.createTransport());
    }

    @Test
    void rejectsUnknownTransportType() {
        RemoteExecutorTransportFactory factory = factory(
                new RecordingRemoteTransport(CommunicationType.GRPC),
                new RecordingRemoteTransport(CommunicationType.KAFKA),
                "LOCAL");

        assertThrows(IllegalArgumentException.class, factory::createTransport);
    }

    private static RemoteExecutorTransportFactory factory(
            RemoteExecutorTransport grpcTransport,
            RemoteExecutorTransport kafkaTransport,
            String transportType) {
        RemoteExecutorTransportFactory factory = new RemoteExecutorTransportFactory();
        factory.grpcTransport = grpcTransport;
        factory.kafkaTransport = kafkaTransport;
        factory.transportType = transportType;
        return factory;
    }

    private record RecordingRemoteTransport(CommunicationType communicationType) implements RemoteExecutorTransport {
        @Override
        public CommunicationType getCommunicationType() {
            return communicationType;
        }

        @Override
        public Multi<NodeExecutionTask> receiveTasks() {
            return Multi.createFrom().empty();
        }

        @Override
        public Uni<Void> sendResult(NodeExecutionResult result) {
            return Uni.createFrom().voidItem();
        }

        @Override
        public Uni<Void> register(List<WorkflowExecutor> executors) {
            return Uni.createFrom().voidItem();
        }

        @Override
        public Uni<Void> unregister() {
            return Uni.createFrom().voidItem();
        }

        @Override
        public Uni<Void> sendHeartbeat() {
            return Uni.createFrom().voidItem();
        }

        @Override
        public Duration getHeartbeatInterval() {
            return Duration.ofSeconds(30);
        }
    }
}
