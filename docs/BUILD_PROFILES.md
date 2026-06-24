# Gamelan Build Profiles

Gamelan uses Gradle profile tasks as the primary build strategy. Built-in
profiles are declared in `gradle/gamelan-profiles.properties`, which keeps
profile strategy maintenance outside Kotlin build logic and makes custom profile
builders cheap to add.

The root build generates lifecycle tasks from that profile file. For a profile
named `agentic-local`, Gradle creates tasks such as `gamelanAgenticLocalCompile`,
`gamelanAgenticLocalTest`, `gamelanAgenticLocalCheck`, and
`gamelanAgenticLocalBuild`.

The profile builder implementation lives in the `buildlogic.gamelan-profiles`
convention plugin under `build-logic`; the root `build.gradle.kts` only applies
that plugin and declares project coordinates.

Included Gradle projects are declared in `gradle/gamelan-modules.properties`.
The settings script validates that catalog and maps each `project.<name>` entry
to the matching Gradle project path. Machine-local module overlays can be added
with `gradle/gamelan-modules.local.properties`, and CI/product-specific module
catalogs can be passed with `-Pgamelan.module.files=...`.

Java module conventions live in `buildlogic.java-conventions`. Project group,
version, Java release, repository selection, and publication behavior are
configured through `gradle.properties`; artifact publication is opt-in via
`gamelan.publish.enabled=true`.

Dependency capability choices that affect profile builds are also centralized in
`gradle.properties`, including the Quarkus/Kafka `lz4-java` provider selection.

## Profiles

| Profile | Modules Added |
| --- | --- |
| `core` | Minimal engine SPI, plugin SPI, engine core, SDK core/local modules |
| `agentic-local` | Local-first agent orchestration profile: core engine, DAG extension, local runtime, local client/executor SDKs |
| `business-automation` | Business automation and EIP server profile: core engine, DAG extension, server app, executor registry, protocols |
| `server` | Quarkus engine app and executor registry |
| `protocols` | gRPC and Kafka protocol modules |
| `runtimes` | Standalone and distributed runtime modules |
| `executor` | Executor registry/runtime modules and executor SDK transports |
| `client` | Local and remote client SDK modules |
| `plugins` | Built-in plugin modules |
| `extensions` | Optional extension modules, currently `gamelan-dag` |
| `examples` | Example clients, executors, and plugin demos |
| `all` | Every Gradle subproject with a build file |

The built-in source of truth is `gradle/gamelan-profiles.properties`; run
`./gradlew gamelanProfiles` to print the exact active profile matrix and active
catalog source files. Runtime-bearing Gradle profiles can also declare
`profile.<name>.runtime-contract` so build-time profile validation and runtime
capability contracts stay aligned before the application starts.

## Persistence Profiles

Workflow state and agentic AI context persistence are selected by config:

| Config | Store | Intended Profile |
| --- | --- | --- |
| `gamelan.workflow.persistence.store=memory` | In-memory workflow definitions, runs, tokens, callbacks, and history | tests, ephemeral dev |
| `gamelan.workflow.persistence.store=file` | Local JSON files under `gamelan.workflow.persistence.file.root` | local, standalone, coding-agent workflows |
| `gamelan.workflow.persistence.store=postgres` | PostgreSQL workflow tables, history, dead letters, and durable idempotency markers created by Flyway/schema migrations | server, distributed, prod/cloud |
| `gamelan.workflow.persistence.file.history-log-compaction-bytes=1048576` | Compact local append-only history logs after this many pending bytes; `0` disables compaction | local, standalone, coding-agent workflows |
| `gamelan.task-queue.file.root` | Optional override for file-backed queued node tasks; defaults under the workflow file store root | local, standalone, coding-agent workflows |
| `gamelan.task-dead-letter.file.root` | Optional override for file-backed task dead letters; defaults under the workflow file store root | local, standalone, coding-agent workflows |
| `gamelan.workflow.wakeup.dead-letter-audit.file.root` | Optional override for file-backed workflow wake-up dead-letter audit records; defaults under the workflow file store root | local, standalone, coding-agent workflows |
| `gamelan.workflow.wakeup.dead-letter-audit.retention.enabled=false` | Enables profile-driven workflow wake-up dead-letter audit retention when a scheduler, admin job, or operator endpoint invokes the configured retention service | local/operator, server, distributed |
| `gamelan.workflow.wakeup.dead-letter-audit.retention.older-than` | Optional age threshold for configured audit retention, using Quarkus duration values such as `30d` or `720h` | local/operator, server, distributed |
| `gamelan.workflow.wakeup.dead-letter-audit.retention.retain-latest=-1` | Optional number of latest matching audit records to keep before age filtering; `-1` disables count retention | local/operator, server, distributed |
| `gamelan.workflow.wakeup.dead-letter-audit.retention.dry-run=true` | Default execution mode for configured audit retention; keep true until the profile is verified | local/operator, server, distributed |
| `gamelan.workflow.wakeup.dead-letter-audit.retention.lease.owner-id` | Optional owner id used when a durable recovery lease backend coordinates retention across runtime instances | distributed/server observability |
| `gamelan.workflow.wakeup.dead-letter-audit.retention.lease.ttl=5m` | Durable retention lease TTL when file/PostgreSQL recovery lease storage is available | distributed/server safety |
| `gamelan.workflow.wakeup.dead-letter-audit.retention.schedule.enabled=false` | Enables the runtime scheduler adapter that periodically invokes configured audit retention | managed runtimes, production maintenance |
| `gamelan.workflow.wakeup.dead-letter-audit.retention.schedule.interval=5m` | Scheduler interval for configured audit retention checks | managed runtimes, production maintenance |
| `gamelan.workflow.wakeup.dead-letter-audit.retention.schedule.dry-run` | Optional dry-run override used only by scheduled retention invocations | staged rollout, production maintenance |
| `gamelan.workflow.wakeup.dead-letter-audit.retention.query.*` | Optional configured audit retention filters: `operation`, `outcome`, `intent-id`, `run-id`, `tenant-id`, `dry-run`, and `limit` | tenant-specific retention, production controls |
| `gamelan.agent.context.store=file` | Local text files under `gamelan.agent.context.local.root` | local, standalone, coding-agent workflows |
| `gamelan.agent.context.store=postgres` | PostgreSQL table `agent_context_documents`, created by Flyway/schema migrations | server, distributed, prod/cloud |

The workflow file store persists definitions, run snapshots, execution token
hashes, callback token hashes, execution history, and idempotency markers for
processed node results and external callback signals. Tenant directories use
deterministic hashes instead of raw tenant IDs, so path-like tenant values cannot
escape the configured local store root; the stored JSON still contains the real
tenant ID. Definition and run mutations use atomic replace semantics plus
sidecar file locks, so separate local engine JVMs do not lose read-modify-write
updates while sharing the same workspace store. Execution history uses
append-only `.jsonl` logs under the same sidecar locks and still reads legacy
snapshot-style `.json` history files, avoiding whole-history rewrites for
long-running workflows while preserving local store compatibility. History logs
are read line-by-line, and a crash-damaged trailing record is ignored while
previous durable records remain readable. Large history logs are compacted back
into snapshot files under the same locks; event-id deduplication prevents stale
post-compaction logs from double-counting events after a crash. The compaction
threshold is profile/configurable with
`gamelan.workflow.persistence.file.history-log-compaction-bytes`, and nonpositive
values leave local history as pure append-only logs. Run-level
locks and execution-history/idempotency files are scoped by tenant and run id,
so local multi-tenant agent workloads with colliding run ids do not serialize
unrelated tenant work or share processed markers. When the workflow persistence
store is `file`, queued node tasks and task dead letters are also stored as
local JSON files and survive runtime restarts. File-backed queued tasks use
visibility leases, stale-ack rejection, and expired-lease redelivery, which lets
offline coding agents and standalone runtimes resume abandoned local work after
the lease expires. Claim scans lease a configurable bounded batch, avoiding lease
storms and repeated full-directory scans when a local/offline agent restarts with
a large backlog. Unreadable local queue records are isolated and skipped during
claim scans so a crash-damaged task file does not block delivery of other valid
workflow work; operators can inspect or remove the damaged JSON separately.
The file queue also exposes read-only backlog stats for available, leased,
expired, unreadable, and total records, giving future operator resources a cheap
way to inspect local queue health without claiming work. Queue stats include an
observed timestamp and a derived health state (`IDLE`, `ACTIVE`, `BACKLOG`,
`STALE_LEASES`, `UNREADABLE_RECORDS`, or `UNKNOWN`) so dashboards and alerts can
use the active queue profile without reimplementing count precedence.
The active task queue exposes the same stats contract at
`GET /api/v1/task-queue/stats`; queue implementations without native inspection
return an explicit unknown snapshot instead of fabricating counts.
Workflow wake-up dead-letter operator actions are auditable through
`GET /api/v1/workflow-wakeup-dead-letter-audit`,
`GET /api/v1/workflow-wakeup-dead-letter-audit/count`, and
`GET /api/v1/workflow-wakeup-dead-letter-audit/summary`. File and PostgreSQL
profiles persist these audit records across restarts; in-memory/noop profiles
can remain ephemeral for tests. Audit retention is exposed through
`POST /api/v1/workflow-wakeup-dead-letter-audit/purge` and is guarded by
default: callers must provide at least one audit filter or `all=true`, must
provide `olderThanSeconds` or `retainLatest`, and the operation defaults to
dry-run. Set `dryRun=false` only when the selected candidates should be deleted;
use `dryRunFilter` to filter historical audit records whose original operator
action was itself a dry-run. Configured retention can be inspected at
`GET /api/v1/workflow-wakeup-dead-letter-audit/retention/policy` and invoked at
`POST /api/v1/workflow-wakeup-dead-letter-audit/retention/run`; current
single-flight state and the last retention attempt are exposed at
`GET /api/v1/workflow-wakeup-dead-letter-audit/retention/status`. `force=true`
runs a configured policy even when `retention.enabled=false`, while `dryRun`
overrides only that invocation. Concurrent invocations return a skipped result
with reason `retention_already_running`, preventing scheduler/operator stampedes
inside one runtime. When a file or PostgreSQL recovery lease repository is
available, configured retention also acquires the durable lease
`maintenance:workflow-wakeup-dead-letter-audit-retention`; another runtime
holding that lease produces skipped reason `retention_lease_unavailable`. This
service-level contract lets future schedulers, CLIs, and distributed maintenance
jobs share the same policy builder instead of duplicating retention logic.
The scheduler adapter is disabled by default and can be enabled with
`gamelan.workflow.wakeup.dead-letter-audit.retention.schedule.enabled=true`.
It calls the configured retention service on
`gamelan.workflow.wakeup.dead-letter-audit.retention.schedule.interval`, uses the
same in-process and durable lease guards, and exposes scheduler-level state at
`GET /api/v1/workflow-wakeup-dead-letter-audit/retention/schedule/status`.
Use `retention.schedule.dry-run` only when the scheduled invocations should
override the base retention dry-run setting. Overlapping scheduler ticks are
reported as `schedule_already_running`, and a missing retention service is
reported as `retention_service_unavailable`.
Retention emits low-cardinality metrics:
`gamelan.workflow.wakeup.dead_letter.audit.retention.runs{outcome,reason,dry_run,lease}`,
`gamelan.workflow.wakeup.dead_letter.audit.retention.duration{outcome,reason,dry_run,lease}`,
and `gamelan.workflow.wakeup.dead_letter.audit.retention.records{kind,dry_run}`
where `kind` is `selected` or `purged`. Reasons are bounded to configured skip
codes and `failure`, so dashboards can alert on disabled/misconfigured retention,
lease contention, failed purges, or unexpectedly high purge volume without
tenant/run cardinality.
The active worker exposes read-only runtime status at
`GET /api/v1/task-worker/status`, including lifecycle state, subscription
activity, in-flight task identifiers, effective concurrency and timeout config,
and worker failure counters. This keeps local/offline agents and distributed
operators from depending on log scraping to understand whether work is actively
being dispatched or waiting for redelivery.
Operators can control the active worker with `POST /api/v1/task-worker/pause`,
`POST /api/v1/task-worker/drain`, and `POST /api/v1/task-worker/resume`. Pause
stops claiming new queue work while existing dispatches continue, drain waits for
the in-flight set using the normal shutdown grace period, and resume is rejected
while older in-flight work is still draining to avoid exceeding configured
worker concurrency.
`GET /api/v1/task-runtime/status` aggregates the active worker snapshot, queue
stats, and task dead-letter count in one failure-tolerant response with
machine-readable health and issue codes. If queue or dead-letter storage is
unavailable, the endpoint still returns worker status and marks the affected
component unavailable with an error summary.
`GET /api/v1/task-runtime/readiness` exposes the same classification as a probe
response, returning HTTP 503 for `DEGRADED` runtimes and for non-degraded health
states rejected by the active readiness policy, while
`GET /api/v1/task-runtime/liveness` is a process-level heartbeat that does not
touch queue or dead-letter storage.
Task-runtime status and readiness calls bound queue and dead-letter diagnostics
with `gamelan.task-runtime.status.component-timeout`, so a stalled Redis,
PostgreSQL, file, or custom backend is reported as unavailable instead of
pinning probes indefinitely. Each component status also reports diagnostic
duration, timeout classification, and observation timestamp so operators can
distinguish unavailable stores from slow-but-successful stores.
Set `gamelan.task-runtime.status.cache-ttl` above zero to reuse one local status
snapshot across frequent status/readiness probes; cache metadata in the response
marks whether the returned snapshot was a hit and when it expires.
`GET /api/v1/runtime/capabilities` reports the active runtime profile,
persistence/agent/registry strategy config, the selected capability contract,
core component implementations, executor adapters, task dispatchers,
gRPC task-stream brokers, recovery lease repositories, wake-up outboxes,
event-publisher diagnostics, wake-up publisher diagnostics, runtime
execution-context state, probe settings, and a derived `health` summary with
stable issue codes. This gives local agents, operators, and CI profile checks a
single low-cost view of which pluggable pieces are active in the current
process.
`GET /api/v1/runtime/health` returns just that derived health summary, and
`GET /api/v1/runtime/readiness` returns HTTP 503 only when required runtime
capabilities are unavailable; degraded optional diagnostics remain visible but
do not fail readiness by default. Set
`gamelan.runtime.capabilities.readiness.accept-degraded=false` for strict CI or
production probes that should reject degraded capability health.
Readiness exposes the full stable issue-code list plus a bounded set of detailed
issue objects, total issue count, and truncation metadata so noisy runtimes stay
cheap to probe while startup and deployment tooling can still show actionable
component/message context. The readiness and startup detail limits are
profile-configurable, allowing local/offline agent profiles to return richer
diagnostics while distributed and production profiles keep probe payloads small.
Set `gamelan.runtime.capabilities.cache-ttl` above zero to reuse one local
capability snapshot across frequent capabilities, health, and readiness probes;
capabilities and readiness responses include cache metadata so operators can
distinguish fresh observations from bounded cached snapshots.
Capability health also checks concrete profile drift for workflow
definition/run/history repositories, configured agent context stores, task
queue/dead-letter/retry components, event-publisher family, wake-up outbox
store, gRPC task-stream brokers, and recovery lease repositories, so a runtime
that advertises `postgres`, `file`, `redis`, or `kafka` but loads an
incompatible implementation is visible before workflow durability or delivery
assumptions are broken.
Startup validation reuses the same capability health classifier. By default it
logs profile/capability drift as a warning during boot; production or CI profiles
can set `gamelan.runtime.capabilities.startup-validation.mode=fail` to reject an
invalid runtime before any workflow is accepted.
Capability contracts make those expectations explicit per profile. Set
`gamelan.runtime.capabilities.contract=auto` to infer from the active Quarkus
profile, or choose `local`, `offline-agent`, `standalone`, `distributed`,
`production`, or `none`. Distributed contracts require shared workflow
persistence, non-memory registry persistence, available event/wake-up publishers,
and a shared gRPC task-stream broker when stream delivery is enabled.
Production contracts require PostgreSQL or custom durable workflow persistence,
PostgreSQL or custom agent-context persistence, non-memory registry persistence,
and available event/wake-up publishers. For concrete `postgres` or `file` store
profiles, the contract also verifies discovered repository and agent-context
implementations match the configured durability family instead of trusting
configuration alone. For concrete `redis` gRPC task-stream profiles, it also
verifies the discovered stream broker matches the configured delivery family.
Production and distributed contracts require
`gamelan.event.publisher.family=kafka`, a Kafka event publisher implementation,
`gamelan.workflow.wakeup.outbox.store=postgres`, a PostgreSQL wake-up outbox,
`gamelan.scheduler.mode=redis`, Redis-backed queued task/retry components, and a
workflow recovery lease repository that matches the configured workflow
persistence family.
Offline-agent contracts require file-backed workflow and agent-context stores so
local coding-agent state can survive process restarts without a cloud database;
they also require a local/default wake-up publisher with a file-backed wake-up
outbox so missed run-drive notifications are repairable after process restart.
Redis stream task queues map Redis consumer-group `lag` to available backlog and
`pending` to leased/in-flight work; if a Redis server or group cannot expose
those fields, the stats endpoint returns `UNKNOWN` instead of misleading
operators. Redis task queues also normalize canonical delivery metadata on both
enqueue and consume, so legacy stream entries and newly queued work expose the
same delivery-attempt, defer-count, and first-seen semantics as memory and file
queues. Before reading new Redis Stream entries, Redis task queues reclaim stale
pending entries with `XAUTOCLAIM`; this lets another runtime redeliver work when
a previous consumer crashed or stopped before acknowledging the stream message.
Reclaimed Redis messages are treated as redeliveries and increment the canonical
delivery-attempt metadata while preserving defer count and first-seen timestamp.
Message IDs are hashed for file names so path-like queue IDs
cannot escape the configured queue or DLQ directories. The PostgreSQL profile uses
`workflow_events`, `workflow_processed_node_results`,
`workflow_processed_external_signals`, and `task_dead_letters` for the same
execution-history, duplicate-delivery, and operator-recovery semantics in
cloud/server deployments; event sequence numbers are scoped by tenant and run id,
and generic legacy history lookups read only the `system` event stream to avoid
cross-tenant leakage when run ids collide. The lower-level `EventStore` SPI also exposes
tenant-aware overloads, and the PostgreSQL event store enforces expected-version
checks under the same tenant/run advisory lock; multi-event appends run inside a
single database transaction so a conflict cannot leave a partially committed event
batch, and event payloads are serialized before the transaction starts to keep
database lock windows short. Event replay fails fast when a stored event cannot
be deserialized, preventing corrupted history from being silently skipped. The same `AgentContextStore` SPI is used for `AGENTS.md`,
`SKILL.md`, prompt logs, and thread history. Runtime repositories do not create
tables; schema is owned by migrations. See `docs/AGENT_CONTEXT_PERSISTENCE.md`.

## Executor Selection Profiles

Executor lookup is centralized in the registry so workflow orchestration,
queued task workers, and future schedulers use the same health, type, placement,
and strategy rules.

| Config / Metadata | Behavior | Intended Profile |
| --- | --- | --- |
| `gamelan.registry.selection.strategy=round-robin` | Default node-aware executor balancing strategy | default |
| `gamelan.registry.selection.strategy=random` | Random executor selection among compatible healthy executors | tests, simple pools |
| `gamelan.registry.selection.strategy=weighted` | Weighted least-connections using heartbeat task counts and executor weight/capacity metadata | heterogeneous worker pools |
| `gamelan.registry.selection.strategy=least-loaded` | Prefer the executor with the lowest heartbeat load relative to max concurrency | distributed worker pools |
| Executor metadata `gamelan.executor.selection.weight=10` | Increase weighted strategy capacity/preference for stronger workers | mixed CPU/GPU/local-model pools |
| Executor metadata `gamelan.executor.max-concurrent-tasks=10` | Declare executor concurrency capacity for least-loaded/weighted scoring and saturation backpressure | agent/tool workers with known capacity |
| `gamelan.registry.selection.prefer-local=true` | Prefer compatible local executors before applying the strategy | standalone, local agent/coding-agent workflows |
| `gamelan.registry.selection.prefer-local=false` | Let the configured strategy select across local and remote executors | server, distributed, cloud |
| Node config `__executor_selection__={"strategy":"weighted"}` | Override selection strategy for one node/request without changing the runtime profile | mixed agent/business workloads |
| Node config `__executor_selection_strategy__=weighted` | Shortcut for strategy-only node routing overrides | simple workflow definitions |
| Node config `__executor_selection__={"requiredCapabilities":["coding","sandbox"]}` | Require executor capability metadata before strategy selection | agent/tools/domain-specialized worker pools |
| Node config `__executor_selection__={"preferredCapabilities":["browser"]}` | Soft-bias selection toward matching executors while allowing fallback | mixed pools with optional accelerators/tools |
| Node config `__executor_selection__={"excludedCapabilities":["pii"]}` | Reject executors that advertise forbidden capabilities | compliance, tenancy, data-residency boundaries |
| Node config `__executor_selection__={"minMemoryMb":4096,"minCpuCores":2}` | Require minimum executor resource metadata before strategy selection | local models, sandboxed coding agents, heavy ETL |
| Node config `__executor_selection__={"regions":["eu-west-1"],"dataResidency":"eu"}` | Restrict execution to matching region/data-residency metadata | regulated domains, tenant-local processing |
| Executor metadata `gamelan.executor.capabilities` | Declares executor capabilities such as `coding`, `browser`, `finance`, or `sandbox` | agent orchestration, tool routing, business domains |
| Executor metadata `gamelan.executor.resources.memory-mb` | Declares available executor memory in MB | resource-aware routing |
| Executor metadata `gamelan.executor.resources.cpu-cores` | Declares available executor CPU cores | resource-aware routing |
| Executor metadata `gamelan.executor.resources.regions` | Declares supported regions such as `us-east-1` or `eu-west-1` | regional routing |
| Executor metadata `gamelan.executor.resources.data-residencies` | Declares supported data-residency scopes such as `us` or `eu` | regulated domains |
| Executor metadata `gamelan.placement.runtimes` | Declares supported runtimes such as `local`, `remote`, or `distributed` | agent orchestration, sandbox pools |
| Executor metadata `gamelan.placement.isolations` | Declares supported isolation such as `none` or `sandbox` | sandboxed agents/tools |

When heartbeat task count reaches `gamelan.executor.max-concurrent-tasks`, the
registry withholds that executor from selection and reports
`capacity-saturated` in selection diagnostics. This gives local and distributed
workflow schedulers a shared backpressure signal instead of overloading full
agents or business workers. If the metadata key is present, its value must be a
positive integer; invalid values are rejected with `invalid-capacity-metadata`
instead of being treated as unlimited capacity.
Executor selection rejection strings are defined by
`ExecutorSelectionRejectionReasons`, which keeps registry diagnostics, scheduler
defer reasons, task dead-letter records, and operator filters on the same stable
reason taxonomy. `ExecutorSelectionReport` exposes the derived primary rejection
reason and permanent-rejection classification, so schedulers and workflow
orchestrators do not need to parse raw rejection-count maps.

Workflow validation rejects malformed `__executor_selection__` values, invalid
resource requirements, and impossible capability combinations such as requiring
and excluding the same capability before the workflow is executed.

## Task Delivery Profiles

Executor delivery is transport-agnostic at the engine boundary. Push transports
dispatch directly to executor endpoints; gRPC stream executors can opt into
pull-style delivery without changing workflow definitions.

| Config / Metadata | Behavior | Intended Profile |
| --- | --- | --- |
| `gamelan.grpc.task-stream.default-enabled=false` | Preserve existing unary gRPC push behavior unless executor metadata opts in | default, server |
| `gamelan.grpc.task-stream.default-enabled=true` | Route all gRPC executors through the server-stream inbox broker | stream-first runtime profiles |
| `gamelan.grpc.task-stream.broker=memory` | Use process-local stream inboxes | local, tests, standalone |
| `gamelan.grpc.task-stream.in-memory.ack-timeout=5m` | Redeliver an ACKed memory-stream task if no completion arrives before the local lease expires | local, tests, standalone |
| `gamelan.grpc.task-stream.broker=redis` | Use Redis Streams plus Redis in-flight indexes for multi-instance delivery | distributed/server |
| `gamelan.grpc.task-stream.redis.reclaim-idle-timeout=5m` | Reclaim pending Redis Stream tasks whose consumer did not complete before the lease expires | distributed/server |
| `gamelan.grpc.task-stream.redis.reclaim-batch-size=100` | Maximum stale pending messages reclaimed per poll | distributed/server |
| `gamelan.grpc.task-stream.redis.assignment-claim-ttl=30s` | Temporary duplicate-dispatch claim TTL while Redis stream assignment is being written | distributed/server |
| `gamelan.scheduler.mode=local` | Use process-local retry scheduling and the queue family selected by workflow persistence | local, standalone, offline agent |
| `gamelan.scheduler.mode=redis` | Require Redis-backed task queue and retry manager wiring for multi-instance execution | distributed/server, production |
| `gamelan.event.publisher.family=local` | Use the default in-process/event-bus publisher family | local, standalone, offline agent |
| `gamelan.event.publisher.family=kafka` | Require Kafka event publisher wiring; production/distributed configs also select `tech.kayys.gamelan.kafka.KafkaEventPublisher` | distributed/server, production |
| `gamelan.workflow.wakeup.outbox.store=auto` | Derive the wake-up outbox family from `gamelan.workflow.persistence.store` | embedded defaults, custom profiles |
| `gamelan.workflow.wakeup.outbox.store=file` | Require file-backed wake-up recovery for local/offline workflows | standalone, coding-agent workflows |
| `gamelan.workflow.wakeup.outbox.store=postgres` | Require PostgreSQL-backed wake-up recovery for multi-instance runtimes | distributed/server, production |
| `gamelan.task-queue.in-memory.lease-duration=30s` | Visibility lease assigned to in-memory queued tasks before unacknowledged work is redelivered | local, tests, standalone |
| `gamelan.task-queue.in-memory.lease-scan-interval=1s` | How often the in-memory queue scans for expired task leases | local, tests, standalone |
| `gamelan.task-queue.file.lease-duration=30s` | Visibility lease assigned to file-backed queued tasks before unacknowledged work is redelivered | local, standalone, coding-agent workflows |
| `gamelan.task-queue.file.lease-scan-interval=1s` | How often the file-backed queue scans for expired task leases | local, standalone, coding-agent workflows |
| `gamelan.task-queue.file.claim-batch-size=100` | Maximum file-backed queued tasks leased during one scan, bounding local backlog delivery and filesystem work | local, standalone, coding-agent workflows |
| `gamelan.task-queue.redis.block-timeout=1s` | Maximum Redis Stream blocking read wait while polling for queued node tasks | distributed/server |
| `gamelan.task-queue.redis.read-batch-size=10` | Maximum new or reclaimed Redis Stream tasks returned per poll | distributed/server |
| `gamelan.task-queue.redis.reclaim-idle-timeout=5m` | Redis pending task idle time before another runtime can reclaim and redeliver it | distributed/server |
| `gamelan.task-queue.redis.reclaim-batch-size=100` | Upper bound for stale Redis pending tasks considered for reclaim per poll, capped by read batch size | distributed/server |
| `gamelan.task-worker.max-concurrent-tasks=64` | Maximum queued tasks one engine worker processes concurrently from the active task queue | backlog control, agent/tool pools, distributed workers |
| `gamelan.task-worker.lease-renewal.enabled=true` | Renew queue visibility leases while a worker dispatch is still in flight | long-running agents, local executors, distributed queues |
| `gamelan.task-worker.lease-renewal.interval=10s` | How often workers renew leased queued tasks during dispatch | long-running agents, local executors, distributed queues |
| `gamelan.task-worker.lease-renewal.duration=30s` | Visibility lease extension requested on each worker renewal | file/in-memory queues, Redis idle renewal |
| `gamelan.task-worker.consume-retry.initial-backoff=1s` | Initial backoff before resubscribing after the queue consumer stream fails | Redis disconnects, file watcher/runtime faults |
| `gamelan.task-worker.consume-retry.max-backoff=30s` | Maximum backoff between queue consumer stream resubscriptions | Redis disconnects, file watcher/runtime faults |
| `gamelan.task-worker.dispatch-timeout=5m` | Maximum time a worker waits for dispatcher handoff before releasing the worker slot and leaving the task for redelivery | hung dispatchers, broken transports, sandbox handoff faults |
| `gamelan.task-worker.shutdown-grace-period=30s` | Maximum time shutdown waits for already in-flight task dispatches after stopping queue consumption | rolling deploys, local agents, sandboxed executors |
| `gamelan.runtime.execution.shutdown-grace-period=5s` | Maximum time the runtime execution context waits for its internal executor to terminate before force-cancelling queued work | rolling deploys, embedded/local runtimes |
| `gamelan.task-worker.max-delivery-attempts=100` | Dead-letter poison queued tasks after repeated queue redelivery before executor lookup | file/Redis queues, crash recovery, operator triage |
| `gamelan.task-worker.no-executor.defer-delay=1s` | Requeue/defer queued node work when registry selection finds no compatible capacity | saturated pools, rolling deploys, autoscaling |
| `gamelan.task-worker.no-executor.max-defers=30` | Dead-letter queued node work after repeated no-executor deferrals | production queues, operator triage |
| `gamelan.task-runtime.status.component-timeout=2s` | Maximum time status/readiness waits for queue stats or dead-letter count before marking that component unavailable | probes, stalled stores, pluggable persistence |
| `gamelan.task-runtime.status.cache-ttl=0s` | Optional per-process status/readiness snapshot TTL; `0s` disables caching | high-frequency probes, expensive diagnostics |
| `gamelan.task-runtime.readiness.accept-unknown=true` | Treat `UNKNOWN` aggregate task-runtime health as ready instead of returning HTTP 503 | local/offline queues, optional queue stats |
| `gamelan.task-runtime.readiness.accept-stale-leases=true` | Treat `STALE_LEASES` aggregate task-runtime health as ready instead of returning HTTP 503 | self-healing queues, rolling deploys |
| `gamelan.task-runtime.readiness.accept-backlog=true` | Treat `BACKLOG` aggregate task-runtime health as ready instead of returning HTTP 503 | autoscaling, bursty agent/tool pools |
| `gamelan.runtime.capabilities.contract=auto` | Select capability expectations; supported values are `auto`, `none`, `local`, `offline-agent`, `standalone`, `distributed`, and `production` | profile verification, local/offline agents, distributed runtimes |
| `gamelan.runtime.capabilities.cache-ttl=0s` | Optional per-process runtime capability snapshot TTL; `0s` disables caching | high-frequency probes, expensive diagnostics |
| `gamelan.runtime.capabilities.readiness.accept-degraded=true` | Treat degraded runtime capability health as ready at `/api/v1/runtime/readiness` | optional diagnostics, local/offline profile tolerance |
| `gamelan.runtime.capabilities.readiness.issue-detail-limit=20` | Maximum detailed runtime capability issues returned by `/api/v1/runtime/readiness`; stable issue codes and total issue count remain complete | probe payload control, noisy profile diagnostics |
| `gamelan.runtime.capabilities.startup-validation.mode=warn` | Validate runtime capability/profile wiring at startup; supported values are `disabled`, `warn`, and `fail` | production guardrails, CI profile verification |
| `gamelan.runtime.capabilities.startup-validation.accept-degraded=true` | In `fail` mode, allow degraded-but-usable capability health instead of aborting startup | staged rollouts, optional publishers, strict production profiles |
| `gamelan.runtime.capabilities.startup-validation.issue-detail-limit=20` | Maximum detailed runtime capability issues included in startup validation summaries | boot diagnostics, profile-specific log volume |
| Executor metadata `gamelan.grpc.delivery=stream` | Route that executor through `ExecutorService.StreamTasks` | local agents, coding agents, worker pools |
| Blank gRPC executor endpoint | Treated as stream delivery, because unary push has no endpoint to call | pull/stream executors |

Deferred queued tasks carry delivery metadata inside the task payload
(`__queue_delivery_attempt__`, `__queue_defer_count__`,
`__queue_first_seen_at__`, and `__queue_last_defer_reason__`) so retry bounds
survive queue implementations that only persist `NodeExecutionTask`.
`TaskQueueMetadata` owns the canonical keys, parsing defaults, defer mutation,
and replay cleanup rules. Queue implementations should opt into
`TaskQueueContract` so enqueue/consume, defer metadata, and metadata stripping
remain consistent across local, Redis, and future broker-backed profiles.
`TaskQueue.QueuedTask` also carries queue-owned lease metadata (`leaseId` and
`leaseExpiresAt`), and workers acknowledge the full queued task rather than only
the message id. Simple queues can keep the compatibility `acknowledge(String)`
path; durable queues should override `acknowledge(QueuedTask)` and `renewLease`
to reject stale acknowledgements after a visibility timeout.
Workers apply `gamelan.task-worker.max-concurrent-tasks` while consuming the
active queue, so a sudden backlog cannot force unbounded registry selection,
dispatch, or local-agent handoff concurrency inside one engine process. Tune this
alongside executor capacity metadata and queue batch sizes: queue batch size
controls how much work can be claimed per scan/poll, while worker concurrency
controls how many claimed tasks are processed at once.
Task processing failures are isolated per queued message: the worker logs the
failure and leaves the message unacknowledged for normal lease expiry/redelivery,
but the consumer stream keeps running so one bad dispatcher call or transient
repository failure cannot stop the whole worker loop.
The queue consumer stream itself is also supervised with configurable backoff;
if a queue implementation fails the stream because of a Redis disconnect or local
runtime fault, the worker resubscribes instead of staying permanently stopped.
Shutdown is graceful by default: the worker stops the consume subscription,
keeps active task dispatches shielded from that cancellation, and waits up to
`gamelan.task-worker.shutdown-grace-period` for the in-flight set to drain before
stopping lease-renewal timers. If the grace period expires, remaining
message/lease/run/node identifiers are logged so operators can correlate later
redelivery or long-running agent activity.
The runtime shutdown observer invokes the same worker drain path before closing
the runtime execution context. Drain failures are logged and do not prevent
later shutdown steps, so one stuck worker control path does not skip other local
resource cleanup. The runtime execution context then waits up to
`gamelan.runtime.execution.shutdown-grace-period` for its internal executor to
terminate and force-cancels queued work if the bound expires. Runtime execution
threads use the `gamelan-runtime-exec-*` name prefix, making thread dumps and
local-agent diagnostics attributable without custom JVM flags. The same
execution-context state is included in `GET /api/v1/runtime/capabilities`, and
shutdown emits `gamelan.runtime.execution.shutdowns{outcome,forced,interrupted}`,
`gamelan.runtime.execution.shutdown.duration{outcome,forced,interrupted}`, and
`gamelan.runtime.execution.shutdown.cancelled_tasks{outcome,interrupted}`.
Worker dispatch handoff is bounded by `gamelan.task-worker.dispatch-timeout`.
This timeout protects the worker slot and lease-renewal loop from a dispatcher
that never returns; it is not the node execution timeout, which remains part of
workflow recovery and node definition semantics.
Workers renew active queue leases while dispatch is in flight, which prevents
slow local executors, coding agents, sandboxed tools, or remote handoff paths
from being redelivered only because dispatch exceeded the queue visibility
timeout. Redis stream queues expose the Redis pending-entry idle timeout as the
lease window and renew by verifying the message is still owned by the current
consumer before resetting its idle timer. Keep the renewal interval lower than
the active queue lease/reclaim timeout.
Workers enforce `gamelan.task-worker.max-delivery-attempts` before executor
selection, so a poison task reclaimed repeatedly from a file queue or Redis
consumer group is moved to the dead-letter queue instead of cycling forever.
The configured value is inclusive: attempt `100` is still allowed when the limit
is `100`, while attempt `101` is dead-lettered with reason
`max-delivery-attempts-exceeded`.
Permanent selection problems such as
`invalid-capacity-metadata`, or transient problems that exceed the defer budget,
are published to `TaskDeadLetterQueue` before the original queue message is
acknowledged. Task dead-letter diagnostics include the worker decision
(`defer` or `dead-letter`), primary selection reason or dead-letter reason,
permanent-failure flag, delivery attempt, defer count, configured defer or
delivery-attempt budget, lease metadata when available, and whether the relevant
budget was exhausted. Runtime profiles expose recent task dead letters at
`GET /api/v1/task-dead-letters`, `GET /api/v1/task-dead-letters/count`, and
`DELETE /api/v1/task-dead-letters` for local/operator triage. Operators can
delete one resolved item with `DELETE /api/v1/task-dead-letters/{messageId}` or
clear a targeted subset by passing `runId`, `nodeId`, `tenantId`, or `reason` to
`DELETE /api/v1/task-dead-letters`; calling the collection delete without
filters remains an explicit full clear. Operators can requeue one recovered item
with `POST /api/v1/task-dead-letters/{messageId}/requeue`, or bulk-requeue a
bounded filtered set with `POST /api/v1/task-dead-letters/requeue`. Bulk requeue
requires at least one filter unless `all=true` is supplied, processes entries
sequentially, stops after the first failure, and reports selected, requeued,
failed, and skipped counts. Replay strips queue delivery metadata from the task
and deletes the dead-letter entry only after the task is accepted by the active
queue. If dead-letter cleanup cannot be confirmed, replay fails loudly after
enqueue instead of hiding possible duplicate operator action. Local file
persistence profiles and PostgreSQL server profiles keep these records durable
across process restarts, while ephemeral profiles fall back to the in-memory
default. Queue implementations should opt into `TaskDeadLetterQueueContract` so
publish/list/count/get/delete/clear semantics, tenant-aware filtering, ordering,
and record normalization stay consistent across in-memory, local file/offline
agent, and Postgres/cloud persistence profiles. Dead-letter list and count
endpoints accept `runId`, `nodeId`, `tenantId`, `reason`, and `limit` query
parameters for targeted inspection.

The broker contract is `GrpcTaskStreamBroker`. `InMemoryGrpcTaskStreamBroker`
is the default fallback for local use and keeps ACKed tasks leased until result
completion or local timeout. `RedisGrpcTaskStreamBroker` is selected by
`gamelan.grpc.task-stream.broker=redis` for distributed runtimes. Redis-backed
stream delivery uses one task-owner claim per task id to reject duplicate
dispatch, keeps stream ACK separate from executor ACK, and only sends Redis
`XACK` after task result completion. Executor ACK refreshes the task lease;
Redis reclaim skips claimed messages whose Gamelan ACK lease is still active.
Additional PostgreSQL, Kafka, or cloud-queue brokers can replace the SPI without
changing dispatcher or workflow code.

External executor results enter through `WorkflowRunManager.onNodeExecutionCompleted`.
Issued execution tokens are stored by `WorkflowRunRepository.storeToken`; gRPC
and Kafka result ingestion present the returned token as the callback signature.
For tenant-scoped runs, newly issued execution tokens are bound to the tenant and
validated with run id, tenant id, node id, attempt, expiry, and stored token hash;
legacy null-tenant token rows remain valid only as compatibility wildcards for
already-issued work. When both a transport/request tenant and token tenant are
present, the engine rejects mismatches before taking the run lock, so a valid
executor token cannot be replayed into a different tenant that happens to share a
run id.
The engine also exposes `handleNodeResultWithOutcome` and
`onNodeExecutionCompletedWithOutcome` for internal integrations that need a typed
completion result (`ACCEPT`, `ALREADY_PROCESSED`, `STALE`, `ALREADY_APPLIED`,
or `RUN_NOT_ACCEPTING_RESULTS`)
without scraping logs or inferring from side effects. Existing `Uni<Void>`
methods remain the stable compatibility surface.
Accepted and already-applied node result history includes the `acceptance`
metadata value; first-seen stale results append `NODE_RESULT_IGNORED` before
writing the processed marker. Results that arrive after a run is terminal or in
compensation mode are recorded as `RUN_NOT_ACCEPTING_RESULTS` ignored history and
marked processed without mutating node or workflow state, so late executor
deliveries remain auditable without reapplying workflow state changes.
External signal callbacks are registered through `WorkflowRunRepository.storeCallback`
and verified against the target run before the signal is accepted. Tenant-aware
registrations bind callback tokens to the tenant; null-tenant callback records
remain valid as legacy wildcards. Callback HTTP ingestion resolves the request
tenant through the normal runtime tenant context before verification, so
tenant-scoped callback signals cannot mutate a different tenant's run. Repositories
store bearer token hashes for execution and callback tokens, not raw bearer token
values. Successfully handled external callback signals are marked processed by
callback-token hash in execution history, so duplicate callback deliveries are
ignored without replaying workflow state mutation across memory, file, and
PostgreSQL persistence profiles. Signals that arrive after a run is terminal or
in compensation mode append `SIGNAL_IGNORED` history and are still marked
processed, preserving idempotency and auditability without buffering unsafe
future mutations. Callback HTTP endpoints accept tokens through `Authorization: Bearer ...`
or `X-Gamelan-Callback-Token`; the `token` query parameter remains only as a
compatibility fallback. Callback signal bodies use the concrete external-signal
shape `{ "signalType": "...", "targetNodeId": "...", "payload": { ... } }` and
missing signal type or target node is rejected at the resource boundary. The
trusted internal engine path remains `handleNodeResult`.

Workflow lifecycle commands are guarded by the aggregate state machine before
storage mutation. Repeated terminal `cancelRun` requests remain idempotent wakeups,
but a run already in `COMPENSATING` cannot be cancelled again; compensation must
finish through `completeCompensation` or `failCompensation`. Likewise,
`completeCompensation` is accepted only while the run is compensating, preventing
operator or replay paths from marking unrelated active runs as compensated.
When failure or cancellation starts compensation, the run remains active:
`completedAt` stays empty until compensation reaches `COMPENSATED` or `FAILED`,
and execution history records `COMPENSATION_STARTED` with the nodes selected for
compensation before publishing the compensating wake-up. Compensation finalization
also has explicit lifecycle history: `COMPENSATION_COMPLETED` records the closed
compensation state, and `COMPENSATION_FAILED` records the remaining work plus the
normalized failure code. Compensation history event names are defined by the
shared `CompensationEventTypes` SPI catalog, while compensation failure codes
and default messages are defined by `CompensationErrors`, so runtime profiles,
audit sinks, and orchestration extensions can filter or normalize compensation
signals without duplicating string literals. Compensation event metadata keys and
stable values are defined by `CompensationHistoryMetadata`, covering claim ids,
leases, failure sources, skip reasons, and state snapshots. Runtime code should
create append payloads through the typed `CompensationHistoryRecords` SPI
factory when emitting compensation audit records. The compensation coordinator
executes rollbacks in deterministic reverse execution order and
reports overall failure if any mandatory compensation node fails, even when
policy allows later nodes to keep running after the first error. The coordinator
enforces compensation policy execution settings per node: `timeout` bounds
rollback work, `maxRetries` retries transient failures, and direct compensation
requests are rejected for non-compensable run states such as `RUNNING` or
`COMPLETED`. Each successful
rollback node is persisted under the run lock, audited as
`COMPENSATION_NODE_COMPLETED`, and marked with a durable
processed-compensation-node marker before the coordinator moves on. The marker
is implemented by all persistence profiles: in-memory, local file/offline agent
stores, and Postgres/cloud stores. Repository implementations should opt into
the `ExecutionHistoryRepositoryHistoryContract`,
`ExecutionHistoryRepositoryCompensationMarkerContract`, and
`ExecutionHistoryRepositoryIdempotencyMarkerContract` Gradle test fixtures from
`gamelan-engine-spi` so audit history, compensation markers, node-result
markers, and external-signal markers stay tenant-scoped and preserve legacy
global fallback across profiles. Run repositories should also opt into
`WorkflowDefinitionRepositoryContract` and `WorkflowRunRepositoryContract` so
definition activation lifecycle, run snapshots, recovery queries, surgical
context/node updates, execution tokens, and callbacks remain portable across
local/offline and server/cloud persistence profiles.
If compensation resumes after a restart or
partial rollback, the coordinator uses the persisted `CompensationState` and
dispatches only the remaining uncompensated nodes. Duplicate per-node
compensation completions are idempotent while compensation is still active, so
retried worker acknowledgements do not corrupt rollback progress. Persisted
run state is also checked before each compensation handler is invoked, so a
stale coordinator or retry loop skips nodes that another coordinator already
persisted as compensated instead of running duplicate rollback side effects.
For active concurrent coordinators, each rollback node is claimed under the run
lock with a durable lease before the handler runs. A second coordinator will not
execute the same rollback while that lease is active, and sequential
compensation will stop instead of jumping ahead to earlier rollback nodes and
breaking reverse-order semantics. The default claim lease is profile-configurable
with `gamelan.workflow.compensation.claim-lease` and defaults to `15m`; a positive
workflow compensation policy `timeout` still takes precedence and adds a
one-minute recovery buffer. Set `gamelan.workflow.compensation.coordinator-id`
to a stable pod, host, sandbox, or local-agent id when operators need to trace
which runtime owns a compensation claim; the default `auto` value generates a
unique process-local id. State-changing claim and release operations append
`COMPENSATION_NODE_CLAIMED` and `COMPENSATION_NODE_CLAIM_RELEASED` history events
on a best-effort path. Expired-claim takeovers append
`COMPENSATION_NODE_CLAIM_EXPIRED` with the previous claim id, owner, and expiry,
and blocked attempts append `COMPENSATION_NODE_CLAIM_SKIPPED` with the active
claim id, owner, and expiry.
Operators can inspect lease behavior without turning audit storage hiccups into
stranded rollback work. Failed rollback nodes append best-effort
`COMPENSATION_NODE_FAILED` history with the claim id, failure source, type, and
message before the claim is released; this keeps per-node rollback failures
visible even when the aggregate compensation attempt later reports
`COMPENSATION_FAILED`. Nodes skipped because persisted state already shows them
as compensated append `COMPENSATION_NODE_SKIPPED`, so stale coordinators and
retry loops leave an audit trail without re-running rollback side effects. Persisted
compensation state is normalized on restore: duplicate nodes are collapsed in
order, already-compensated nodes are removed from the pending list, terminal
states get a terminal timestamp, and completed states cannot retain pending
rollback work. Command methods also enforce the terminal boundary directly:
completed compensation cannot be failed later, failed compensation cannot be
completed later, and late per-node progress is rejected unless it is an
idempotent acknowledgement for an already-compensated node.

## Examples

Build only the engine and SDK:

```bash
./gradlew gamelanCoreTest
```

Build local-first agent orchestration pieces:

```bash
./gradlew gamelanAgenticLocalTest
```

Build business automation / EIP server pieces:

```bash
./gradlew gamelanBusinessAutomationCheck
```

Build optional extensions:

```bash
./gradlew gamelanExtensionsTest
```

Run every Gradle-backed module:

```bash
./gradlew gamelanAllBuild
```

## Gradle Profile Builder

Use the generated profile tasks for common slices:

```bash
# Core engine and SDK roots
./gradlew gamelanCoreBuild

# Server roots; project dependencies are pulled transitively
./gradlew gamelanServerBuild

# Executor-side roots
./gradlew gamelanExecutorBuild
```

Use the property-driven task for custom combinations:

```bash
./gradlew gamelanProfileBuild -Pgamelan.profile=core,server
./gradlew gamelanProfileTest -Pgamelan.profile=core,extensions
./gradlew gamelanProfileProjects -Pgamelan.profile=core,server
```

Available Gradle lifecycle suffixes are `Compile`, `Assemble`, `Test`, `Check`,
and `Build`, for example `gamelanCoreTest` or `gamelanAllAssemble`.

For fully custom builders, use `gamelanProfileRun` with both profile and target
task selected by properties:

```bash
./gradlew gamelanProfileRun -Pgamelan.profile=core,server -Pgamelan.task=classes
./gradlew gamelanProfileRun -Pgamelan.profile=plugins,extensions -Pgamelan.task=check
```

Print available Gradle profiles and roots:

```bash
./gradlew gamelanProfiles
```

Print included Gradle projects and directories:

```bash
./gradlew gamelanProjects
```

Add shared Gradle modules in `gradle/gamelan-modules.properties`:

```properties
project.my-domain-module=domains/my-domain-module
```

For machine-local module inclusion, copy
`gradle/gamelan-modules.local.properties.example` to
`gradle/gamelan-modules.local.properties`. The local file is ignored by git and
overrides the built-in module catalog when the same project name is declared.

For CI or product-specific module inclusion, pass one or more external catalogs:

```bash
./gradlew gamelanProjects -Pgamelan.module.files=../profiles/domain-modules.properties
```

Add shared custom profile builders in `gradle/gamelan-profiles.properties`:

```properties
profile.my-domain.description=My domain-specific workflow profile
profile.my-domain.projects=:gamelan-engine-spi,:gamelan-plugin-spi,:gamelan-engine-core,:gamelan-dag
```

Profiles that include runtime-serving modules such as `:gamelan-engine`,
`:gamelan-runtime-core`, `:gamelan-runtime-standalone`, or
`:gamelan-runtime-distributed` must also declare their intended runtime
capability contract:

```properties
profile.my-offline-agent.description=Offline coding-agent workflow profile
profile.my-offline-agent.projects=:gamelan-engine-spi,:gamelan-engine-core,:gamelan-runtime-core,:gamelan-runtime-standalone,:gamelan-sdk-client-local,:gamelan-sdk-executor-local
profile.my-offline-agent.runtime-contract=offline-agent
```

Supported Gradle profile runtime contracts are `none`, `local`,
`offline-agent`, `standalone`, `distributed`, and `production`. The
`gamelanValidateRuntimeCapabilityContracts` task checks that runtime-bearing
profiles declare a contract and that contract-specific module expectations are
not violated, for example an `offline-agent` profile must include the standalone
runtime and local SDKs, while a `distributed` profile must include the
distributed runtime.

After adding that profile, Gradle automatically exposes tasks like
`gamelanMyDomainTest`, `gamelanMyDomainCheck`, and `gamelanMyDomainBuild`.

For machine-local profile builders, copy
`gradle/gamelan-profiles.local.properties.example` to
`gradle/gamelan-profiles.local.properties`. The local file is ignored by git and
overrides the built-in catalog when the same profile name is declared.

For CI or product-specific builders, pass one or more external catalogs. Later
catalogs override earlier ones:

```bash
./gradlew gamelanProfiles -Pgamelan.profile.files=../profiles/ai.properties
./gradlew gamelanProfileBuild -Pgamelan.profile=my-domain -Pgamelan.profile.files=../profiles/domain.properties
```

Validate profile catalogs without running module tasks:

```bash
./gradlew gamelanValidateProfiles
```

`gamelanValidateProfiles` also depends on
`gamelanValidateRuntimeCapabilityContracts`,
`gamelanValidateRuntimeCapabilityConfig`, and `gamelanValidateMigrations`.
The runtime config guard checks that standalone, distributed, default engine,
and production engine application properties declare the expected runtime
capability contracts, persistence stores, event-publisher family, wake-up outbox
store, scheduler mode, registry persistence, readiness policy, capability cache
TTL, readiness/startup issue detail limits, and startup validation mode. It also checks that strict
Kafka profiles select
`tech.kayys.gamelan.kafka.KafkaEventPublisher`. The migration guard checks that
standalone and distributed Flyway migration catalogs use contiguous versions and
identical SQL. Run focused guards directly when changing only one profile
concern:

```bash
./gradlew gamelanValidateRuntimeCapabilityContracts
./gradlew gamelanValidateRuntimeCapabilityConfig
./gradlew gamelanValidateMigrations
```

Runtime HTTP smoke tests are opt-in because they require a runnable Quarkus
application and datasource/container setup:

```bash
./gradlew gamelanRuntimeTest -Dgamelan.runtime.http.tests=true
```

## Design Rule

The engine core should compile without domain-specific integrations. Agentic AI,
EIP, storage, model providers, business-system connectors, and transport-specific
behavior should enter through SPI contracts, plugins, or optional modules. This
keeps Gamelan domain-agnostic while still allowing first-class workflow support
for each domain.
