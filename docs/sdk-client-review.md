

## ✅ What’s already good

### 1. Clean public API

```java
client.workflows().create("order-flow")...
client.runs().create("order-flow").input("x", 1).execute();
```

This is:
✔ readable
✔ fluent
✔ hides transport details
✔ future-proof for more transports (Kafka, A2A, MCP, etc)

Very good SDK ergonomics.

---

### 2. Transport abstraction is correct

```java
WorkflowRunClient
WorkflowDefinitionClient
```

with:

* REST impl
* gRPC impl

This is **exactly** how SDKs like Temporal, Camunda, Stripe do it internally.

Design score: **9/10**

---

### 3. Builders are well designed

`CreateRunBuilder`, `SuspendRunBuilder`, `SignalBuilder`, etc:

* immutable client
* mutable request state
* late execution

This matches real-world SDKs (AWS SDK v2 style).

---

## ⚠️ Major functional gaps (important)

### 1. gRPC clients are empty (currently unusable)

Example:

```java
@Override
public Uni<RunResponse> createRun(CreateRunRequest request) {
    checkClosed();
    return null;
}
```

Same in:

* `GrpcWorkflowRunClient`
* `GrpcWorkflowDefinitionClient`

👉 This means:
*SDK compiles but runtime will NPE.*

**Fix recommendation**
At minimum:

```java
return Uni.createFrom().failure(
    new UnsupportedOperationException("gRPC transport not implemented yet")
);
```

Otherwise users will get silent null bugs.

---

### 2. REST client: many methods return `null`

In `RestWorkflowRunClient`:

```java
@Override
public Uni<RunResponse> suspendRun(...) {
    return null;
}
```

Unimplemented:

* suspendRun
* resumeRun
* cancelRun
* signal
* queryRuns
* getActiveRunsCount

This is dangerous in an SDK because:
👉 user thinks feature exists
👉 runtime NPE later

**Better:**

```java
return Uni.createFrom().failure(
    new UnsupportedOperationException("Not implemented yet")
);
```

---

### 3. Inconsistent error handling

Sometimes:

```java
throw new RuntimeException("Failed to deserialize...")
```

Sometimes:

```java
return Uni.createFrom().failure(...)
```

You should standardize:
👉 **always return failed Uni**
Never throw inside `.map()`.

Bad:

```java
.map(r -> { throw new RuntimeException(); })
```

Good:

```java
.onItem().transformToUni(r -> 
    Uni.createFrom().item(mapper.readValue(...))
)
.onFailure().transform(e -> new GamelanClientException(...))
```

I strongly recommend introducing:

```java
public class GamelanClientException extends RuntimeException {
    private final int statusCode;
}
```

---

## ⚠️ Configuration & lifecycle issues

### 4. Vertx lifecycle is risky

In `GamelanClient`:

```java
this.vertx = Vertx.vertx();
...
vertx.close();
```

Problem:
If user already has Vertx, they cannot reuse it.

**Better design:**

```java
Builder.vertx(Vertx vertx)
```

and:

```java
if (ownedVertx) vertx.close();
```

Otherwise embedding in Quarkus apps becomes messy.

---

### 5. REST endpoint parsing is fragile

You do:

```java
if (endpoint.startsWith("http"))
```

This breaks for:

* https+path (`https://host/api`)
* future LB URLs

Better:
Parse once in config:

```java
URI baseUri;
```

Store:

```java
config.baseUri().getHost()
config.baseUri().getPort()
config.baseUri().getScheme()
```

---

## ⚠️ Serialization mismatch risk

In `createRun()`:

```java
.sendJson(request)
```

But other places:

```java
mapper.writeValueAsString(dto)
```

This mixes:

* Vertx JSON codec
* Jackson mapper

Risk:
👉 inconsistent date handling
👉 polymorphic nodes break
👉 mismatch with engine API

**Recommendation:**
Standardize on Jackson everywhere:

```java
JsonObject body = new JsonObject(mapper.writeValueAsString(request));
```

---

## ✅ Architecture-level suggestions (important)

### 6. Add Client Interceptors (very valuable)

You already have:

```java
config.headers()
```

But you will soon want:

* auth refresh
* tracing
* retry
* logging

Add:

```java
interface ClientInterceptor {
   HttpRequest<?> apply(HttpRequest<?> req);
}
```

Then:

```java
for (var i : config.interceptors()) {
   req = i.apply(req);
}
```

This makes SDK enterprise-ready.

---

### 7. Domain separation is excellent

You did:

* sdk.client
* engine.workflow
* engine.run

This is **very good** because:
SDK = transport
Engine = domain

You are not leaking REST DTOs into SDK API 👍
This is correct layering.

---

## ⚠️ Missing: pagination & filters

`queryRuns`:

```java
Uni<List<RunResponse>> queryRuns(...)
```

You should plan:

```java
class RunPage {
   List<RunResponse> items;
   long total;
   int page;
}
```

Otherwise future UI integrations will suffer.

---

## 🔒 Security concern

In REST client:

```java
.putHeader("Authorization", "Bearer " + config.apiKey())
```

But also:

```java
applyAuthHeaders()
```

This can cause:

* double headers
* mismatch behavior

Pick **one auth strategy**:
Prefer:

```java
applyAuthHeaders()
```

everywhere.

---

## ⭐ Overall rating

| Area                        | Score  |
| --------------------------- | ------ |
| Public API design           | 9/10   |
| Transport abstraction       | 9/10   |
| Builders                    | 9/10   |
| Implementation completeness | ❌ 4/10 |
| Error handling              | 6/10   |
| Production readiness        | 6/10   |

**Design = strong.
Implementation = half finished.**

This is actually good news — the hard part (API design) is done.

---


# SUGGESTION

## CONCERN
✅ Architecture correctness
✅ What must NOT be in SDK
✅ What must be added
✅ What must be fixed
✅ How it should look after refactor
✅ How Wayang should use it
✅ How embedded mode should work
---

# ✅ 1. Architectural verdict (important)

Your current split is **architecturally correct**:

```
Wayang Runtime
   |
   v
Gamelan SDK Client  (REMOTE CONTROL ONLY)
   |
   v
Gamelan Engine (orchestrator, dispatcher, scheduler)
```

**SDK client MUST NOT contain:**
❌ WorkflowOrchestrator
❌ TaskDispatcher
❌ Scheduler
❌ State machine
❌ Plugin system

Those correctly live in:

```
gamelan-engine
gamelan-engine-core
```

Your current SDK already follows this rule ✔
So conceptually: **you are right already**.

---

# ⚠️ 2. Critical problems in your current SDK code

These are real production issues:

## (A) Many methods return `null`

Example:

```java
public Uni<RunResponse> suspendRun(...) {
    return null;
}
```

This will cause:
💥 NullPointerException
💥 silent runtime bugs
💥 broken API contract

### ✅ Fix rule

**Never return null. Always return Uni failure:**

```java
return Uni.createFrom().failure(
    new UnsupportedOperationException("Not implemented yet")
);
```

Do this for:

* suspend
* resume
* cancel
* signal
* queryRuns
* getActiveRunsCount
* gRPC methods

---

## (B) gRPC client is a stub (dangerous)

Your:

```
GrpcWorkflowRunClient
GrpcWorkflowDefinitionClient
```

currently do:

```java
return null;
```

This is worse than not having gRPC at all.

### ✅ Minimum safe behavior

```java
return Uni.createFrom().failure(
   new GamelanClientException("gRPC transport not implemented")
);
```

Later you can wire real stubs.

---

## (C) Error handling is inconsistent

You mix:

```java
throw new RuntimeException(...)
```

and:

```java
Uni.createFrom().failure(...)
```

This breaks reactive pipelines.

### ✅ Rule

**SDK must never throw inside map() or callbacks.**
Always return failed Uni:

Introduce:

```java
public class GamelanClientException extends RuntimeException {
    private final int statusCode;
    public GamelanClientException(String msg, int code) { ... }
}
```

Then:

```java
.onFailure().transform(e -> 
    new GamelanClientException("Call failed", 500)
)
```

---

# ⚠️ 3. Lifecycle & configuration problems

## (A) Vertx ownership problem

You do:

```java
this.vertx = Vertx.vertx();
...
vertx.close();
```

This breaks Quarkus embedding.

### ✅ Fix

Support injection:

```java
GamelanClientConfig {
   Vertx vertx;
   boolean managedVertx;
}
```

Logic:

```java
if (config.vertx() != null) use it;
else create one and mark managed=true;
```

On close:

```java
if (managedVertx) vertx.close();
```

---

## (B) Endpoint parsing is fragile

You use:

```java
if (endpoint.startsWith("http"))
```

This will break with:

```
https://host/api/v1
```

### ✅ Fix

Parse once:

```java
URI baseUri = URI.create(endpoint);
```

Then always:

```java
client.request(
   baseUri.getPort(),
   baseUri.getHost(),
   baseUri.getPath() + "/runs"
)
```

---

# ⚠️ 4. Serialization risk

You mix:

* Vertx JSON
* Jackson mapper

### ✅ Rule

SDK must use **Jackson only** to match engine DTOs:

```java
String json = mapper.writeValueAsString(request);
request.sendBuffer(Buffer.buffer(json));
```

No:

```java
.sendJson(dto)
```

This avoids:

* polymorphic errors
* date errors
* versioning mismatch

---

# ✅ 5. What SDK client SHOULD contain (final form)

Your SDK client responsibilities should be exactly:

```
sdk-client
 ├── GamelanClient (facade)
 ├── WorkflowDefinitionClient
 ├── WorkflowRunClient
 ├── Transport (REST / gRPC)
 ├── Builders (CreateRunBuilder, SignalBuilder)
 ├── DTO mappers
 ├── Auth handling
 ├── Interceptors
```

And nothing from:

```
dispatcher
orchestration
scheduler
executor
```

---

# ⭐ 6. Missing feature you should add: Interceptors

You will need:

* auth refresh
* tracing
* retry
* logging
* metrics

Add:

```java
public interface ClientInterceptor {
    HttpRequest<Buffer> apply(HttpRequest<Buffer> req);
}
```

In config:

```java
List<ClientInterceptor> interceptors;
```

In REST client:

```java
HttpRequest<Buffer> req = webClient.post(...);

for (var i : config.interceptors()) {
    req = i.apply(req);
}
```

This makes SDK enterprise-ready.

---

# 🧩 7. Embedded mode (important for your architecture)

You SHOULD support this:

```
TransportType.LOCAL
```

Implementation:

```java
class LocalWorkflowRunClient implements WorkflowRunClient {
   private final WorkflowOrchestrator orchestrator;

   public Uni<RunResponse> createRun(CreateRunRequest req) {
       return Uni.createFrom().item(orchestrator.start(req));
   }
}
```

This allows:

```
Wayang (single JVM)
  -> Gamelan SDK (LOCAL)
      -> Gamelan Engine Core
```

This is PERFECT for:
✔ tests
✔ offline mode
✔ standalone mode
✔ dev mode

And does NOT break microservice mode.

---

# 🧠 8. How Wayang should depend on this SDK

Wayang should depend ONLY on:

```
gamelan-sdk-client
```

And use:

```java
GamelanClient client = GamelanClient.builder()
   .endpoint("http://gamelan:8080")
   .transport(REST)
   .build();

client.runs()
   .create("myFlow")
   .input("x", 1)
   .execute();
```

Wayang must NOT import:

```
WorkflowOrchestrator
TaskDispatcher
ExecutorDispatcher
```

Which you already respect ✔

---

# 🏗️ 9. Dependency layering (very important)

Correct dependency graph:

```
sdk-client
   -> engine-api (DTOs only)

engine-core
   -> engine-api
   -> plugin-api

engine
   -> engine-core
   -> engine-api
   -> plugin-api
```

SDK must NOT depend on:

```
engine-core
engine
```

From your layout: ✔ this is already true.

---

# 🔥 FINAL VERDICT

Your **design is correct**
Your **API shape is good**
Your **layering is right**

But implementation is:

⚠️ incomplete
⚠️ unsafe (null returns)
⚠️ inconsistent error handling
⚠️ fragile config
⚠️ not embedded-ready

These are mechanical fixes, not conceptual ones.

---

# 🎯 What you should do next (in order)

1. Replace all `return null` with failed Uni
2. Implement full REST client
3. Add interceptor system
4. Add LOCAL transport
5. Standardize Jackson serialization
6. Add `GamelanClientException`
7. Fix Vertx ownership
8. Fix URI parsing
9. Mark gRPC as unsupported until implemented

Goal SDK should be:

✅ safe
✅ production-ready
✅ embeddable
✅ usable by Wayang
✅ usable by external users
✅ transport-agnostic
✅ future-proof

---


