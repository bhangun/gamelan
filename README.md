GAMELAN WORKFLOW ENGINE - PROJECT STRUCTURE
  =========================================

  Module Architecture:

  gamelan-parent/
  ├── gamelan-core/                    # Core workflow engine
  │   ├── domain/                    # Domain models & aggregates
  │   ├── engine/                    # Workflow execution engine
  │   ├── state/                     # State management
  │   ├── persistence/               # Event sourcing & snapshots
  │   └── scheduler/                 # Task scheduling
  │
  ├── gamelan-api/                     # REST API layer
  │   ├── resources/                 # JAX-RS endpoints
  │   ├── dto/                       # API data transfer objects
  │   └── validation/                # Request validation
  │
  ├── gamelan-grpc/                    # gRPC service layer
  │   ├── proto/                     # Protocol buffer definitions
  │   ├── services/                  # gRPC service implementations
  │   └── interceptors/              # gRPC interceptors
  │
  ├── gamelan-kafka/                   # Kafka integration
  │   ├── producers/                 # Event producers
  │   ├── consumers/                 # Event consumers
  │   └── serdes/                    # Custom serializers
  │
  ├── gamelan-client-sdk/              # Client SDK
  │   ├── rest/                      # REST client
  │   ├── grpc/                      # gRPC client
  │   └── builder/                   # Fluent API builders
  │
  ├── gamelan-executor-sdk/            # Executor SDK
  │   ├── executor/                  # Executor base classes
  │   ├── grpc/                      # gRPC executor transport
  │   ├── kafka/                     # Kafka executor transport
  │   └── annotations/               # Executor annotations
  │
  ├── gamelan-registry/                # Service registry & discovery
  │   ├── consul/                    # Consul integration
  │   ├── kubernetes/                # K8s service discovery
  │   └── static/                    # Static configuration
  │
  ├── gamelan-cli/                     # Command-line interface
  │   ├── commands/                  # CLI command implementations
  │   └── client/                    # gRPC client utilities
  │
  └── gamelan-integration-tests/       # End-to-end tests

  Technology Stack:
  - Quarkus 3.x (Reactive & Imperative)
  - Hibernate Reactive with Panache
  - SmallRye Mutiny (Reactive programming)
  - PostgreSQL (Primary data store)
  - Redis (Distributed locking & caching)
  - Kafka (Event streaming)
  - gRPC (High-performance RPC)
  - Consul/K8s (Service discovery)
  - OpenTelemetry (Observability)

  Usage
  =====

  CLI Usage
  ---------

  The Gamelan CLI provides command-line access to the workflow engine via gRPC. Install and run the CLI as follows:

  ```bash
  # Build the CLI module
  mvn clean install -pl gamelan-cli

  # Run the CLI
  java -jar gamelan-cli/target/gamelan-cli-*.jar [OPTIONS] [COMMAND] [SUBCOMMAND] [ARGS]

  # Show help
  gamelan --help
  ```

  ### Workflow Definition Management

  ```bash
  # Create a workflow definition
  gamelan definitions create -t tenant1 -n "My Workflow" -v "1.0" -d "Description" workflow.json

  # Get a workflow definition
  gamelan definitions get -t tenant1 -s localhost:9090 def123

  # List workflow definitions
  gamelan definitions list -t tenant1 --active-only

  # Update a workflow definition
  gamelan definitions update -t tenant1 def123 -d "New description" --active true

  # Delete a workflow definition
  gamelan definitions delete -t tenant1 def123
  ```

  ### Workflow Run Management

  ```bash
  # Create a workflow run
  gamelan runs create -t tenant1 -d workflow123 -i '{"input": "value"}'

  # Get a workflow run
  gamelan runs get -t tenant1 run123

  # Start a workflow run
  gamelan runs start -t tenant1 run123

  # Suspend a workflow run
  gamelan runs suspend -t tenant1 run123 -r "Maintenance"

  # Resume a workflow run
  gamelan runs resume -t tenant1 run123

  # Cancel a workflow run
  gamelan runs cancel -t tenant1 run123 -r "Cancelled by admin"

  # Send a signal to a workflow run
  gamelan runs signal run123 signalName -p '{"data": "value"}'

  # List workflow runs
  gamelan runs list -t tenant1 -d workflow123 -s RUNNING
  ```

  ### Executor Management

  ```bash
  # Register an executor
  gamelan executors register executor123 -t http-executor -c GRPC -e "http://executor:8080" --max-concurrent 20

  # Unregister an executor
  gamelan executors unregister executor123

  # Send heartbeat from executor
  gamelan executors heartbeat executor123
  ```

  Global Options
  --------------
  - `-s, --server`: gRPC server address (host:port), default: localhost:9090
  - `-h, --help`: Show help information
  - `-V, --version`: Show version information

  Common Use Cases
  ----------------
  1. **Deploy a new workflow**: Use `gamelan definitions create` to register a new workflow definition
  2. **Execute a workflow**: Use `gamelan runs create` followed by `gamelan runs start` to execute a workflow
  3. **Monitor workflow execution**: Use `gamelan runs get` to check the status of a running workflow
  4. **Manage executors**: Use the executors commands to register and manage workflow executors

Testing
=======
Gamelan includes comprehensive testing for all components:

### Unit Tests
- Individual component testing using JUnit 5
- API endpoint testing with REST Assured
- Mock-based testing for isolated functionality

### Integration Tests
- Full application integration tests
- End-to-end workflow execution tests
- Multi-module interaction tests

### Running Tests

```bash
# Run all tests
./mvnw test verify

# Run tests for specific module
./mvnw test -pl <module-name>

# Run integration tests only
./mvnw verify -DskipUTs=true
```

  4. **Manage executors**: Use the executors commands to register and manage workflow executors
