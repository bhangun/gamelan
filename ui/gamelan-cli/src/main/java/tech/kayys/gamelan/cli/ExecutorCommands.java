package tech.kayys.gamelan.cli;

import java.util.concurrent.Callable;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import io.grpc.StatusRuntimeException;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;
import tech.kayys.gamelan.grpc.v1.CommunicationType;
import tech.kayys.gamelan.grpc.v1.HeartbeatRequest;
import tech.kayys.gamelan.grpc.v1.RegisterExecutorRequest;
import tech.kayys.gamelan.grpc.v1.UnregisterExecutorRequest;
import tech.kayys.gamelan.grpc.CommunicationTypeConverter;

@Command(name = "executors", description = "Manage executors", subcommands = {
        ExecutorCommands.Register.class,
        ExecutorCommands.Unregister.class,
        ExecutorCommands.Heartbeat.class
})
public class ExecutorCommands {

    @ParentCommand
    GamelanCli parent;

    @Command(name = "register", description = "Register an executor")
    static class Register implements Callable<Integer> {

        @Parameters(index = "0", description = "Executor ID", arity = "1")
        String executorId;

        @Option(names = { "-t", "--type" }, description = "Executor type", required = true)
        String executorType;

        @Option(names = { "-c",
                "--comm-type" }, description = "Communication type (GRPC/KAFKA/REST)", defaultValue = "GRPC")
        String commType;

        @Option(names = { "-e", "--endpoint" }, description = "Endpoint URL")
        String endpoint;

        @Option(names = { "--max-concurrent" }, description = "Max concurrent tasks", defaultValue = "10")
        int maxConcurrentTasks;

        @Option(names = { "-s",
                "--server" }, description = "gRPC server address (host:port)", defaultValue = "localhost:9090")
        String serverAddress;

        @Override
        public Integer call() throws Exception {
            try (GrpcClientManager clientManager = new GrpcClientManager(serverAddress)) {

                RegisterExecutorRequest request = RegisterExecutorRequest.newBuilder()
                        .setExecutorId(executorId)
                        .setExecutorType(executorType)
                        .setCommunicationType(CommunicationTypeConverter.toGrpc(
                                tech.kayys.gamelan.engine.protocol.CommunicationType.valueOf(commType.toUpperCase())))
                        .setEndpoint(endpoint != null ? endpoint : "")
                        .setMaxConcurrentTasks(maxConcurrentTasks)
                        .build();

                var response = clientManager.getExecutorStub()
                        .registerExecutor(request)
                        .await().indefinitely();

                ObjectMapper mapper = new ObjectMapper();
                mapper.registerModule(new JavaTimeModule());

                System.out.println("Executor registered successfully:");
                System.out.println(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(response));

                return 0;
            } catch (StatusRuntimeException e) {
                System.err.println("gRPC error: " + e.getStatus().getDescription());
                return 1;
            } catch (Exception e) {
                System.err.println("Error: " + e.getMessage());
                return 1;
            }
        }
    }

    @Command(name = "unregister", description = "Unregister an executor")
    static class Unregister implements Callable<Integer> {

        @Parameters(index = "0", description = "Executor ID", arity = "1")
        String executorId;

        @Option(names = { "-s",
                "--server" }, description = "gRPC server address (host:port)", defaultValue = "localhost:9090")
        String serverAddress;

        @Override
        public Integer call() throws Exception {
            try (GrpcClientManager clientManager = new GrpcClientManager(serverAddress)) {

                UnregisterExecutorRequest request = UnregisterExecutorRequest.newBuilder()
                        .setExecutorId(executorId)
                        .build();

                clientManager.getExecutorStub()
                        .unregisterExecutor(request)
                        .await().indefinitely();

                System.out.println("Executor unregistered successfully.");

                return 0;
            } catch (StatusRuntimeException e) {
                System.err.println("gRPC error: " + e.getStatus().getDescription());
                return 1;
            } catch (Exception e) {
                System.err.println("Error: " + e.getMessage());
                return 1;
            }
        }
    }

    @Command(name = "heartbeat", description = "Send heartbeat from executor")
    static class Heartbeat implements Callable<Integer> {

        @Parameters(index = "0", description = "Executor ID", arity = "1")
        String executorId;

        @Option(names = { "-s",
                "--server" }, description = "gRPC server address (host:port)", defaultValue = "localhost:9090")
        String serverAddress;

        @Override
        public Integer call() throws Exception {
            try (GrpcClientManager clientManager = new GrpcClientManager(serverAddress)) {

                HeartbeatRequest request = HeartbeatRequest.newBuilder()
                        .setExecutorId(executorId)
                        .build();

                clientManager.getExecutorStub()
                        .heartbeat(request)
                        .await().indefinitely();

                System.out.println("Heartbeat sent successfully.");

                return 0;
            } catch (StatusRuntimeException e) {
                System.err.println("gRPC error: " + e.getStatus().getDescription());
                return 1;
            } catch (Exception e) {
                System.err.println("Error: " + e.getMessage());
                return 1;
            }
        }
    }
}