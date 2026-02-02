# Gamelan SDK Client

The Gamelan SDK Client is the primary entry point for implementing and managing workflows in the Gamelan ecosystem. It provides a fluent, type-safe API for defining workflows, starting runs, and interacting with executors.

## Features

- **Fluent Workflow Definition**: Build complex workflows using a clear, declarative Java API.
- **Multi-Transport Support**: seamless switching between REST, gRPC, and Local transports.
- **Reactive API**: Built on SmallRye Mutiny for non-blocking, high-performance operations.
- **Tenant Isolation**: First-class support for multi-tenancy.

## Quick Start

### 1. Initialize the Client

```java
GamelanClient client = GamelanClient.builder()
    .restEndpoint("http://localhost:8080")
    .tenantId("my-tenant")
    .build();
```

### 2. Define a Workflow

```java
client.defineWorkflow("my-process")
    .startNode("start-task")
    .execute("my-executor")
    .end()
    .build();
```

### 3. Start a Run

```java
Uni<RunResponse> run = client.runs()
    .create("my-process")
    .input("key", "value")
    .execute();
```

## Documentation

- [AGENTS.md](file:///Users/bhangun/Workspace/workkayys/Products/Wayang/wayang-platform/workflow-gamelan/sdk/gamelan-sdk-client/AGENTS.md): Guide for creating and using workflow executors.
