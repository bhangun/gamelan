SILAT WORKFLOW ENGINE - PROJECT STRUCTURE
  =========================================
  
  Module Architecture:
  
  silat-parent/
  ├── silat-core/                    # Core workflow engine
  │   ├── domain/                    # Domain models & aggregates
  │   ├── engine/                    # Workflow execution engine
  │   ├── state/                     # State management
  │   ├── persistence/               # Event sourcing & snapshots
  │   └── scheduler/                 # Task scheduling
  │
  ├── silat-api/                     # REST API layer
  │   ├── resources/                 # JAX-RS endpoints
  │   ├── dto/                       # API data transfer objects
  │   └── validation/                # Request validation
  │
  ├── silat-grpc/                    # gRPC service layer
  │   ├── proto/                     # Protocol buffer definitions
  │   ├── services/                  # gRPC service implementations
  │   └── interceptors/              # gRPC interceptors
  │
  ├── silat-kafka/                   # Kafka integration
  │   ├── producers/                 # Event producers
  │   ├── consumers/                 # Event consumers
  │   └── serdes/                    # Custom serializers
  │
  ├── silat-client-sdk/              # Client SDK
  │   ├── rest/                      # REST client
  │   ├── grpc/                      # gRPC client
  │   └── builder/                   # Fluent API builders
  │
  ├── silat-executor-sdk/            # Executor SDK
  │   ├── executor/                  # Executor base classes
  │   ├── grpc/                      # gRPC executor transport
  │   ├── kafka/                     # Kafka executor transport
  │   └── annotations/               # Executor annotations
  │
  ├── silat-registry/                # Service registry & discovery
  │   ├── consul/                    # Consul integration
  │   ├── kubernetes/                # K8s service discovery
  │   └── static/                    # Static configuration
  │
  └── silat-integration-tests/       # End-to-end tests
  
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
