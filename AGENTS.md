# Wayang Workflow Engine

## Overview

The Wayang Workflow Engine is responsible for parsing, scheduling, and executing complex workflows. It implements sophisticated agent types to manage workflow orchestration, task coordination, and execution monitoring.

## Types

### 1. Workflow Orchestration

#### Workflow Parser
- **Purpose**: Parses workflow definitions and creates execution plans
- **Responsibilities**:
  - Interprets workflow definition languages (JSON/YAML)
  - Validates workflow structure and dependencies
  - Creates execution graphs from workflow definitions
  - Resolves variable references and expressions
- **Location**: `wayang-workflow-engine/gamelan-engine/`

#### Workflow Scheduler
- **Purpose**: Schedules workflow execution based on triggers and dependencies
- **Responsibilities**:
  - Manages workflow execution queues
  - Handles workflow prioritization and resource allocation
  - Implements scheduling algorithms (FIFO, priority-based, etc.)
  - Coordinates with runtime for task execution
- **Location**: `wayang-workflow-engine/gamelan-engine/`

#### Workflow Coordinator
- **Purpose**: Coordinates the execution of complex multi-step workflows
- **Responsibilities**:
  - Manages workflow state transitions
  - Handles workflow branching and merging
  - Coordinates parallel task execution
  - Implements compensation logic for failed workflows
- **Location**: `wayang-workflow-engine/gamelan-engine/`

### 2. Task Management

#### Task Scheduler
- **Purpose**: Schedules individual tasks within workflows
- **Responsibilities**:
  - Determines optimal execution order based on dependencies
  - Allocates resources to scheduled tasks
  - Manages task priorities and deadlines
  - Handles task rescheduling on failures
- **Location**: `wayang-workflow-engine/gamelan-engine/`

#### Task Monitor
- **Purpose**: Monitors the execution status of workflow tasks
- **Responsibilities**:
  - Tracks task execution progress
  - Detects task timeouts and failures
  - Reports task status to workflow coordinator
  - Implements task restart and recovery mechanisms
- **Location**: `wayang-workflow-engine/gamelan-engine/`

#### Dependency Resolver
- **Purpose**: Manages dependencies between workflow tasks
- **Responsibilities**:
  - Analyzes task dependency graphs
  - Determines task execution readiness
  - Handles conditional task execution
  - Manages data flow between dependent tasks
- **Location**: `wayang-workflow-engine/gamelan-engine/`

### 3. Communication and Integration

#### API Client
- **Purpose**: Communicates with external services via APIs
- **Responsibilities**:
  - Executes HTTP requests to external services
  - Handles API authentication and authorization
  - Manages API rate limiting and retries
  - Processes API responses and errors
- **Location**: `wayang-workflow-engine/gamelan-api/`

#### Message Queue
- **Purpose**: Integrates with message queuing systems
- **Responsibilities**:
  - Publishes messages to queues
  - Consumes messages from queues
  - Handles message serialization and deserialization
  - Implements message acknowledgment protocols
- **Location**: `wayang-workflow-engine/gamelan-kafka/`

#### Registry
- **Purpose**: Manages service discovery and registration
- **Responsibilities**:
  - Registers workflow engine instances
  - Discovers available services
  - Maintains service health status
  - Implements load balancing strategies
- **Location**: `wayang-workflow-engine/gamelan-registry/`

#### CLI Agent
- **Purpose**: Provides command-line interface for workflow management
- **Responsibilities**:
  - Offers command-line access to workflow engine functionality
  - Interacts with the engine via gRPC protocol
  - Provides commands for workflow definition management
  - Enables workflow run lifecycle management
  - Supports executor registration and monitoring
  - Formats and displays responses in user-friendly format
- **Location**: `wayang-workflow-engine/gamelan-cli/`

### 4. Event and Trigger

#### Event Processor
- **Purpose**: Processes events that trigger workflow executions
- **Responsibilities**:
  - Listens for triggering events from various sources
  - Filters and validates incoming events
  - Initiates workflow executions based on events
  - Implements event correlation and deduplication
- **Location**: `wayang-workflow-engine/gamelan-engine/`

#### Timer
- **Purpose**: Manages time-based triggers and scheduling
- **Responsibilities**:
  - Handles cron-like scheduling for workflows
  - Manages delayed workflow executions
  - Implements timeout mechanisms for tasks
  - Coordinates with system clocks for accuracy
- **Location**: `wayang-workflow-engine/gamelan-engine/`

#### Condition Evaluator
- **Purpose**: Evaluates conditions that determine workflow flow
- **Responsibilities**:
  - Executes conditional logic in workflows
  - Evaluates boolean expressions and predicates
  - Makes branching decisions based on data
  - Implements complex decision trees
- **Location**: `wayang-workflow-engine/gamelan-engine/`

### 5. Monitoring and Analytics

#### Metrics Collector
- **Purpose**: Collects performance and operational metrics
- **Responsibilities**:
  - Tracks workflow execution times
  - Monitors resource utilization
  - Collects error rates and success metrics
  - Exports metrics to monitoring systems
- **Location**: `wayang-workflow-engine/gamelan-engine/`

#### Workflow Analyzer
- **Purpose**: Analyzes workflow execution patterns and performance
- **Responsibilities**:
  - Identifies bottlenecks in workflows
  - Recommends optimization strategies
  - Analyzes historical execution data
  - Generates performance reports
- **Location**: `wayang-workflow-engine/gamelan-engine/`

## Configuration

### Workflow Engine Configuration

```yaml
workflow_engine:
  core:
    parser:
      max_workflow_depth: 100
      variable_resolution_timeout_ms: 5000
      validation_enabled: true

    scheduler:
      max_concurrent_workflows: 50
      queue_size: 1000
      priority_levels: 5

    task_scheduler:
      max_concurrent_tasks: 100
      task_timeout_seconds: 3600
      retry_attempts: 3

    event_processor:
      max_event_queue_size: 10000
      event_retention_hours: 24
      duplicate_detection_enabled: true

    api_client:
      connection_pool_size: 20
      request_timeout_seconds: 30
      retry_backoff_base: 2
```

## Communication Patterns

### Internal Communication
- Uses gRPC for synchronous communication between engine components
- Employs Kafka for event-driven communication
- Implements shared state management for workflow coordination

### External Communication
- Exposes REST APIs for workflow management
- Provides gRPC endpoints for client integrations
- Implements webhook mechanisms for external event triggers
- Offers command-line interface via CLI agent

## Security Considerations

### Workflow Security
- Input validation for all workflow parameters
- Sandboxing for workflow execution environments
- Access control for workflow definitions and executions
- Encryption of sensitive workflow data

### API Security
- Authentication and authorization for all API endpoints
- Rate limiting to prevent abuse
- Input sanitization to prevent injection attacks
- Secure credential management for external services

## Performance Optimization

### Execution Optimization
- Parallel execution of independent tasks
- Intelligent caching of workflow definitions
- Optimized dependency resolution algorithms
- Resource pooling for improved efficiency

### Scalability Features
- Horizontal scaling across multiple engine instances
- Distributed task scheduling
- Load balancing based on resource availability
- Auto-scaling based on workload demands