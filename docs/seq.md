
Below is a **Mermaid sequence diagram** that shows:

* **Commands** going down via gRPC
* **Facts** flowing up via events
* **Executor emitting to BOTH**:

  * Workflow Engine (**semantic facts**)
  * Control Plane (**observability only**)
* **Workflow Engine remains the authority**

---

### Mermaid Sequence Diagram

(copy–paste into any Mermaid-enabled viewer)

```mermaid
sequenceDiagram
    autonumber

    participant CP as Control Plane
    participant WE as Workflow Engine
    participant EX as Executor
    participant K as Kafka / Event Bus

    %% ---- Command Path (Downward) ----
    CP->>WE: StartWorkflow (gRPC)
    WE->>EX: ExecuteNode (gRPC + ExecutionToken)

    %% ---- Executor Execution ----
    Note over EX: Execute task / agent / integration

    %% ---- Observability Events (Non-semantic) ----
    EX-->>K: ExecutionLog / Metrics / Progress
    K-->>CP: Logs / Metrics / Progress (subscribe)

    %% ---- Semantic Result (Authoritative) ----
    EX-->>K: NodeExecutionResult (SUCCESS / FAILED)
    K-->>WE: NodeExecutionResult

    %% ---- Workflow Semantics Decision ----
    Note over WE: Apply workflow semantics\n- Update state\n- Retry / branch\n- Schedule next nodes

    %% ---- State Notification ----
    WE-->>K: WorkflowStateChanged
    K-->>CP: WorkflowStateChanged

    %% ---- Optional: Next Node ----
    WE->>EX: ExecuteNextNode (gRPC)
```

---

## How to Read This Diagram (Key Points)

### 1. Two Separate “Upward” Channels

| Channel                            | Purpose                 | Authority       |
| ---------------------------------- | ----------------------- | --------------- |
| Executor → Kafka → Control Plane   | Logs, progress, metrics | ❌ No authority  |
| Executor → Kafka → Workflow Engine | NodeExecutionResult     | ✅ Authoritative |

Even though **both use Kafka**, they serve **different semantic layers**.

---

### 2. Control Plane Never Drives Workflow Logic

Notice:

* Control Plane **never reacts directly** to `NodeExecutionResult`
* It only reacts to `WorkflowStateChanged`

This prevents:

* Race conditions
* Inconsistent UI
* Security escalation

---

### 3. Workflow Engine Is Always Notified

Even if:

* Executor streams tokens
* Executor sends progress
* Executor sends logs

👉 **The Workflow Engine still receives the final semantic fact**
That’s the contract.

---

## Enforcing This in Practice (Strong Recommendation)

### Topic-level separation

```text
executor.observability.*
executor.semantic.results
workflow.state.changes
```

### ACL rule

| Producer        | Topic            | Allowed |
| --------------- | ---------------- | ------- |
| Executor        | observability.*  | ✅       |
| Executor        | semantic.results | ✅       |
| Executor        | workflow.state.* | ❌       |
| Workflow Engine | workflow.state.* | ✅       |

This makes violations **physically impossible**.

---

## One-line Mental Model (Diagram Summary)

> **Executors speak facts.
> Workflow Engine interprets facts.
> Control Plane listens to interpretations.**

---

If you want next, I can:

* Convert this into **C4 diagrams**
* Provide **protobuf + event schema**
* Show **Quarkus + Kafka code structure**
* Map this to **Temporal / Cadence equivalence**

Just tell me.
