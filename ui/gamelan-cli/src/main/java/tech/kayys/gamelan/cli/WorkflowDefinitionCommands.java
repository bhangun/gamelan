package tech.kayys.gamelan.cli;

import java.util.concurrent.Callable;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import io.grpc.StatusRuntimeException;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;
import tech.kayys.gamelan.grpc.v1.CreateDefinitionRequest;
import tech.kayys.gamelan.grpc.v1.DeleteDefinitionRequest;
import tech.kayys.gamelan.grpc.v1.GetDefinitionRequest;
import tech.kayys.gamelan.grpc.v1.ListDefinitionsRequest;
import tech.kayys.gamelan.grpc.v1.UpdateDefinitionRequest;

@Command(name = "definitions", description = "Manage workflow definitions", subcommands = {
        WorkflowDefinitionCommands.Create.class,
        WorkflowDefinitionCommands.Get.class,
        WorkflowDefinitionCommands.List.class,
        WorkflowDefinitionCommands.Update.class,
        WorkflowDefinitionCommands.Delete.class
})
public class WorkflowDefinitionCommands {

    @ParentCommand
    GamelanCli parent;

    @Command(name = "create", description = "Create a new workflow definition")
    static class Create implements Callable<Integer> {

        @Option(names = { "-t", "--tenant" }, description = "Tenant ID", required = true)
        String tenantId;

        @Option(names = { "-n", "--name" }, description = "Workflow name", required = true)
        String name;

        @Option(names = { "-v", "--version" }, description = "Version", required = true)
        String version;

        @Option(names = { "-d", "--desc" }, description = "Description")
        String description;

        @Parameters(index = "0", description = "JSON file path containing the workflow definition", arity = "1")
        String jsonFilePath;

        @Option(names = { "-s",
                "--server" }, description = "gRPC server address (host:port)", defaultValue = "localhost:9090")
        String serverAddress;

        @Override
        public Integer call() throws Exception {
            try (GrpcClientManager clientManager = new GrpcClientManager(serverAddress)) {

                // For now, we'll create a minimal request
                // In a real implementation, we would parse the JSON file
                CreateDefinitionRequest request = CreateDefinitionRequest.newBuilder()
                        .setTenantId(tenantId)
                        .setName(name)
                        .setVersion(version)
                        .setDescription(description != null ? description : "")
                        .build();

                var response = clientManager.getWorkflowDefinitionStub()
                        .createDefinition(request)
                        .await().indefinitely();

                ObjectMapper mapper = new ObjectMapper();
                mapper.registerModule(new JavaTimeModule());

                System.out.println("Workflow definition created successfully:");
                System.out.println(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(response));

                return 0;
            } catch (StatusRuntimeException e) {
                System.err.println("gRPC error: " + e.getStatus().getDescription());
                return 1;
            } catch (Exception e) {
                System.err.println("Error: " + e.getMessage());
                e.printStackTrace();
                return 1;
            }
        }
    }

    @Command(name = "get", description = "Get a workflow definition by ID")
    static class Get implements Callable<Integer> {

        @Option(names = { "-t", "--tenant" }, description = "Tenant ID", required = true)
        String tenantId;

        @Parameters(index = "0", description = "Definition ID", arity = "1")
        String definitionId;

        @Option(names = { "-s",
                "--server" }, description = "gRPC server address (host:port)", defaultValue = "localhost:9090")
        String serverAddress;

        @Override
        public Integer call() throws Exception {
            try (GrpcClientManager clientManager = new GrpcClientManager(serverAddress)) {

                GetDefinitionRequest request = GetDefinitionRequest.newBuilder()
                        .setTenantId(tenantId)
                        .setDefinitionId(definitionId)
                        .build();

                var response = clientManager.getWorkflowDefinitionStub()
                        .getDefinition(request)
                        .await().indefinitely();

                ObjectMapper mapper = new ObjectMapper();
                mapper.registerModule(new JavaTimeModule());

                System.out.println("Workflow definition retrieved:");
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

    @Command(name = "list", description = "List workflow definitions")
    static class List implements Callable<Integer> {

        @Option(names = { "-t", "--tenant" }, description = "Tenant ID", required = true)
        String tenantId;

        @Option(names = { "--active-only" }, description = "Show only active definitions", defaultValue = "false")
        boolean activeOnly;

        @Option(names = { "-s",
                "--server" }, description = "gRPC server address (host:port)", defaultValue = "localhost:9090")
        String serverAddress;

        @Override
        public Integer call() throws Exception {
            try (GrpcClientManager clientManager = new GrpcClientManager(serverAddress)) {

                ListDefinitionsRequest request = ListDefinitionsRequest.newBuilder()
                        .setTenantId(tenantId)
                        .setActiveOnly(activeOnly)
                        .build();

                var response = clientManager.getWorkflowDefinitionStub()
                        .listDefinitions(request)
                        .await().indefinitely();

                ObjectMapper mapper = new ObjectMapper();
                mapper.registerModule(new JavaTimeModule());

                System.out.println("Workflow definitions:");
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

    @Command(name = "update", description = "Update a workflow definition")
    static class Update implements Callable<Integer> {

        @Option(names = { "-t", "--tenant" }, description = "Tenant ID", required = true)
        String tenantId;

        @Parameters(index = "0", description = "Definition ID", arity = "1")
        String definitionId;

        @Option(names = { "-d", "--desc" }, description = "New description")
        String description;

        @Option(names = { "--active" }, description = "Set active status (true/false)")
        Boolean isActive;

        @Option(names = { "-s",
                "--server" }, description = "gRPC server address (host:port)", defaultValue = "localhost:9090")
        String serverAddress;

        @Override
        public Integer call() throws Exception {
            try (GrpcClientManager clientManager = new GrpcClientManager(serverAddress)) {

                UpdateDefinitionRequest.Builder builder = UpdateDefinitionRequest.newBuilder()
                        .setTenantId(tenantId)
                        .setDefinitionId(definitionId);

                if (description != null) {
                    builder.setDescription(description);
                }

                if (isActive != null) {
                    builder.setIsActive(isActive);
                }

                var response = clientManager.getWorkflowDefinitionStub()
                        .updateDefinition(builder.build())
                        .await().indefinitely();

                ObjectMapper mapper = new ObjectMapper();
                mapper.registerModule(new JavaTimeModule());

                System.out.println("Workflow definition updated:");
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

    @Command(name = "delete", description = "Delete a workflow definition")
    static class Delete implements Callable<Integer> {

        @Option(names = { "-t", "--tenant" }, description = "Tenant ID", required = true)
        String tenantId;

        @Parameters(index = "0", description = "Definition ID", arity = "1")
        String definitionId;

        @Option(names = { "-s",
                "--server" }, description = "gRPC server address (host:port)", defaultValue = "localhost:9090")
        String serverAddress;

        @Override
        public Integer call() throws Exception {
            try (GrpcClientManager clientManager = new GrpcClientManager(serverAddress)) {

                DeleteDefinitionRequest request = DeleteDefinitionRequest.newBuilder()
                        .setTenantId(tenantId)
                        .setDefinitionId(definitionId)
                        .build();

                clientManager.getWorkflowDefinitionStub()
                        .deleteDefinition(request)
                        .await().indefinitely();

                System.out.println("Workflow definition deleted successfully.");

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