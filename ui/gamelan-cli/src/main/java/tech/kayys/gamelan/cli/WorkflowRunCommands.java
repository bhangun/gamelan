package tech.kayys.gamelan.cli;

import java.util.concurrent.Callable;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import io.grpc.StatusRuntimeException;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;
import tech.kayys.gamelan.grpc.v1.CancelRunRequest;
import tech.kayys.gamelan.grpc.v1.CreateRunRequest;
import tech.kayys.gamelan.grpc.v1.GetRunRequest;
import tech.kayys.gamelan.grpc.v1.QueryRunsRequest;
import tech.kayys.gamelan.grpc.v1.ResumeRunRequest;
import tech.kayys.gamelan.grpc.v1.SignalRequest;
import tech.kayys.gamelan.grpc.v1.StartRunRequest;
import tech.kayys.gamelan.grpc.v1.SuspendRunRequest;

@Command(name = "runs", description = "Manage workflow runs", subcommands = {
        WorkflowRunCommands.Create.class,
        WorkflowRunCommands.Get.class,
        WorkflowRunCommands.Start.class,
        WorkflowRunCommands.Suspend.class,
        WorkflowRunCommands.Resume.class,
        WorkflowRunCommands.Cancel.class,
        WorkflowRunCommands.Signal.class,
        WorkflowRunCommands.List.class
})
public class WorkflowRunCommands {

    @ParentCommand
    GamelanCli parent;

    @Command(name = "create", description = "Create a new workflow run")
    static class Create implements Callable<Integer> {

        @Option(names = { "-t", "--tenant" }, description = "Tenant ID", required = true)
        String tenantId;

        @Option(names = { "-d", "--definition" }, description = "Workflow definition ID", required = true)
        String definitionId;

        @Option(names = { "-i", "--inputs" }, description = "Inputs as JSON string")
        String inputsJson;

        @Option(names = { "-s",
                "--server" }, description = "gRPC server address (host:port)", defaultValue = "localhost:9090")
        String serverAddress;

        @Override
        public Integer call() throws Exception {
            try (GrpcClientManager clientManager = new GrpcClientManager(serverAddress)) {

                CreateRunRequest.Builder builder = CreateRunRequest.newBuilder()
                        .setTenantId(tenantId)
                        .setWorkflowDefinitionId(definitionId);

                if (inputsJson != null && !inputsJson.trim().isEmpty()) {
                    // In a real implementation, we would parse the JSON string to Struct
                    // For now, we'll skip this for simplicity
                }

                var response = clientManager.getWorkflowStub()
                        .createRun(builder.build())
                        .await().indefinitely();

                ObjectMapper mapper = new ObjectMapper();
                mapper.registerModule(new JavaTimeModule());

                System.out.println("Workflow run created successfully:");
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

    @Command(name = "get", description = "Get a workflow run by ID")
    static class Get implements Callable<Integer> {

        @Option(names = { "-t", "--tenant" }, description = "Tenant ID", required = true)
        String tenantId;

        @Parameters(index = "0", description = "Run ID", arity = "1")
        String runId;

        @Option(names = { "-s",
                "--server" }, description = "gRPC server address (host:port)", defaultValue = "localhost:9090")
        String serverAddress;

        @Override
        public Integer call() throws Exception {
            try (GrpcClientManager clientManager = new GrpcClientManager(serverAddress)) {

                GetRunRequest request = GetRunRequest.newBuilder()
                        .setTenantId(tenantId)
                        .setRunId(runId)
                        .build();

                var response = clientManager.getWorkflowStub()
                        .getRun(request)
                        .await().indefinitely();

                ObjectMapper mapper = new ObjectMapper();
                mapper.registerModule(new JavaTimeModule());

                System.out.println("Workflow run retrieved:");
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

    @Command(name = "start", description = "Start a workflow run")
    static class Start implements Callable<Integer> {

        @Option(names = { "-t", "--tenant" }, description = "Tenant ID", required = true)
        String tenantId;

        @Parameters(index = "0", description = "Run ID", arity = "1")
        String runId;

        @Option(names = { "-s",
                "--server" }, description = "gRPC server address (host:port)", defaultValue = "localhost:9090")
        String serverAddress;

        @Override
        public Integer call() throws Exception {
            try (GrpcClientManager clientManager = new GrpcClientManager(serverAddress)) {

                StartRunRequest request = StartRunRequest.newBuilder()
                        .setTenantId(tenantId)
                        .setRunId(runId)
                        .build();

                var response = clientManager.getWorkflowStub()
                        .startRun(request)
                        .await().indefinitely();

                ObjectMapper mapper = new ObjectMapper();
                mapper.registerModule(new JavaTimeModule());

                System.out.println("Workflow run started:");
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

    @Command(name = "suspend", description = "Suspend a workflow run")
    static class Suspend implements Callable<Integer> {

        @Option(names = { "-t", "--tenant" }, description = "Tenant ID", required = true)
        String tenantId;

        @Parameters(index = "0", description = "Run ID", arity = "1")
        String runId;

        @Option(names = { "-r", "--reason" }, description = "Reason for suspension")
        String reason;

        @Option(names = { "-s",
                "--server" }, description = "gRPC server address (host:port)", defaultValue = "localhost:9090")
        String serverAddress;

        @Override
        public Integer call() throws Exception {
            try (GrpcClientManager clientManager = new GrpcClientManager(serverAddress)) {

                SuspendRunRequest.Builder builder = SuspendRunRequest.newBuilder()
                        .setTenantId(tenantId)
                        .setRunId(runId);

                if (reason != null) {
                    builder.setReason(reason);
                }

                var response = clientManager.getWorkflowStub()
                        .suspendRun(builder.build())
                        .await().indefinitely();

                ObjectMapper mapper = new ObjectMapper();
                mapper.registerModule(new JavaTimeModule());

                System.out.println("Workflow run suspended:");
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

    @Command(name = "resume", description = "Resume a workflow run")
    static class Resume implements Callable<Integer> {

        @Option(names = { "-t", "--tenant" }, description = "Tenant ID", required = true)
        String tenantId;

        @Parameters(index = "0", description = "Run ID", arity = "1")
        String runId;

        @Option(names = { "-s",
                "--server" }, description = "gRPC server address (host:port)", defaultValue = "localhost:9090")
        String serverAddress;

        @Override
        public Integer call() throws Exception {
            try (GrpcClientManager clientManager = new GrpcClientManager(serverAddress)) {

                ResumeRunRequest request = ResumeRunRequest.newBuilder()
                        .setTenantId(tenantId)
                        .setRunId(runId)
                        .build();

                var response = clientManager.getWorkflowStub()
                        .resumeRun(request)
                        .await().indefinitely();

                ObjectMapper mapper = new ObjectMapper();
                mapper.registerModule(new JavaTimeModule());

                System.out.println("Workflow run resumed:");
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

    @Command(name = "cancel", description = "Cancel a workflow run")
    static class Cancel implements Callable<Integer> {

        @Option(names = { "-t", "--tenant" }, description = "Tenant ID", required = true)
        String tenantId;

        @Parameters(index = "0", description = "Run ID", arity = "1")
        String runId;

        @Option(names = { "-r", "--reason" }, description = "Reason for cancellation")
        String reason;

        @Option(names = { "-s",
                "--server" }, description = "gRPC server address (host:port)", defaultValue = "localhost:9090")
        String serverAddress;

        @Override
        public Integer call() throws Exception {
            try (GrpcClientManager clientManager = new GrpcClientManager(serverAddress)) {

                CancelRunRequest.Builder builder = CancelRunRequest.newBuilder()
                        .setTenantId(tenantId)
                        .setRunId(runId);

                if (reason != null) {
                    builder.setReason(reason);
                }

                clientManager.getWorkflowStub()
                        .cancelRun(builder.build())
                        .await().indefinitely();

                System.out.println("Workflow run cancelled successfully.");

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

    @Command(name = "signal", description = "Send a signal to a workflow run")
    static class Signal implements Callable<Integer> {

        @Parameters(index = "0", description = "Run ID", arity = "1")
        String runId;

        @Parameters(index = "1", description = "Signal name", arity = "1")
        String signalName;

        @Option(names = { "-p", "--payload" }, description = "Signal payload as JSON string")
        String payloadJson;

        @Option(names = { "-s",
                "--server" }, description = "gRPC server address (host:port)", defaultValue = "localhost:9090")
        String serverAddress;

        @Override
        public Integer call() throws Exception {
            try (GrpcClientManager clientManager = new GrpcClientManager(serverAddress)) {

                SignalRequest.Builder builder = SignalRequest.newBuilder()
                        .setRunId(runId)
                        .setSignalName(signalName);

                if (payloadJson != null && !payloadJson.trim().isEmpty()) {
                    // In a real implementation, we would parse the JSON string to Struct
                    // For now, we'll skip this for simplicity
                }

                clientManager.getWorkflowStub()
                        .signalRun(builder.build())
                        .await().indefinitely();

                System.out.println("Signal sent to workflow run successfully.");

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

    @Command(name = "list", description = "List workflow runs")
    static class List implements Callable<Integer> {

        @Option(names = { "-t", "--tenant" }, description = "Tenant ID", required = true)
        String tenantId;

        @Option(names = { "-d", "--definition" }, description = "Filter by workflow definition ID")
        String definitionId;

        @Option(names = { "--status" }, description = "Filter by status")
        String status;

        @Option(names = { "-s", "--server" }, description = "gRPC server address (host:port)", defaultValue = "localhost:9090")
        String serverAddress;

        @Override
        public Integer call() throws Exception {
            try (GrpcClientManager clientManager = new GrpcClientManager(serverAddress)) {

                QueryRunsRequest.Builder builder = QueryRunsRequest.newBuilder()
                        .setTenantId(tenantId);

                if (definitionId != null) {
                    builder.setWorkflowDefinitionId(definitionId);
                }

                if (status != null) {
                    builder.setStatus(status);
                }

                var response = clientManager.getWorkflowStub()
                        .queryRuns(builder.build())
                        .await().indefinitely();

                ObjectMapper mapper = new ObjectMapper();
                mapper.registerModule(new JavaTimeModule());

                System.out.println("Workflow runs:");
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
}