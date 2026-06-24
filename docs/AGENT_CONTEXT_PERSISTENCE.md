# Agent Context Persistence

Gamelan supports local-first agent context documents and server/cloud persistence
through one SPI:

`tech.kayys.gamelan.engine.agent.context.AgentContextStore`

This is intended for coding-agent style workloads where context is naturally
text based: `AGENTS.md`, `SKILL.md`, prompt logs, thread history, tool notes,
and similar files.

## Strategy

| Profile | Store | Purpose |
| --- | --- | --- |
| local / standalone | `file` | Preserve native text files on disk for local agents |
| server / distributed / prod | `postgres` | Persist the same documents in PostgreSQL for cloud/server runtimes |

Configure the strategy:

```properties
gamelan.agent.context.store=file
gamelan.agent.context.local.root=${GAMELAN_AGENT_CONTEXT_DIR:.gamelan/agent-context}
gamelan.agent.context.max-document-bytes=${GAMELAN_AGENT_CONTEXT_MAX_DOCUMENT_BYTES:8388608}
gamelan.agent.context.max-metadata-bytes=${GAMELAN_AGENT_CONTEXT_MAX_METADATA_BYTES:262144}
gamelan.agent.context.max-list-results=${GAMELAN_AGENT_CONTEXT_MAX_LIST_RESULTS:1000}
```

For cloud/server:

```properties
quarkus.datasource.db-kind=postgresql
gamelan.agent.context.store=postgres
gamelan.agent.context.max-document-bytes=${GAMELAN_AGENT_CONTEXT_MAX_DOCUMENT_BYTES:8388608}
gamelan.agent.context.max-metadata-bytes=${GAMELAN_AGENT_CONTEXT_MAX_METADATA_BYTES:262144}
gamelan.agent.context.max-list-results=${GAMELAN_AGENT_CONTEXT_MAX_LIST_RESULTS:1000}
```

The PostgreSQL implementation uses the configured Quarkus datasource and expects
`agent_context_documents` to be created by Flyway/schema migrations. Repository
code does not create or mutate schema at runtime. Fresh databases include the
table in `V1__initial_schema.sql`; existing databases receive it through
`V5__agent_context_documents.sql`.

## Document Shape

Each document is identified by:

| Field | Example |
| --- | --- |
| tenant | `tenant-1` |
| workspace | `wayang-platform` |
| scope | `workspace`, `skill`, `thread`, `prompt` |
| path | `AGENTS.md`, `java/SKILL.md`, `threads/abc.md` |

`AgentContextQuery` can optionally include a `pathPrefix`, for example
`threads/session-`, so local agents and PostgreSQL runtimes can list only the
relevant folder or document family instead of scanning a whole workspace. It can
also include `maxResults` and an `AgentContextCursor` to page large local
prompt/thread-history listings in deterministic `scope` and `path` order.

`AgentContextDocument` normalizes missing content to an empty text document and
missing content type to `text/markdown`. Content types are capped at 255 UTF-8
bytes and cannot contain control characters, preventing malformed media-type
headers from leaking into file sidecars or PostgreSQL rows. Metadata keys are
also bounded to 128 UTF-8 bytes, must be non-blank, cannot contain control
characters, and metadata values cannot be null. Store-level byte limits still
apply to the final serialized metadata payload.

The file store maps that to:

```text
.gamelan/agent-context/<tenant>/<workspace>/<scope>/<path>
```

`gamelan.agent.context.max-document-bytes` caps each stored document by UTF-8
byte size. The default is `8388608` bytes (8 MiB). Local file persistence rejects
oversized saves/appends before writing and skips oversized manually-created local
files during listing so a stray large prompt log cannot break workspace scans.
PostgreSQL persistence rejects oversized saves before executing SQL and protects
append upserts with an atomic `octet_length` guard.

`gamelan.agent.context.max-metadata-bytes` caps metadata sidecars/JSON payloads
by UTF-8 byte size. The default is `262144` bytes (256 KiB). Local file
persistence validates merged metadata before mutating content and ignores
oversized manually-created metadata sidecars while still loading recoverable
content. PostgreSQL persistence rejects oversized metadata before SQL execution
and protects append upserts with an atomic merged-metadata `octet_length` guard.

`gamelan.agent.context.max-list-results` caps each store listing call. The
default is `1000` documents. If callers omit `maxResults`, file and PostgreSQL
stores use this bounded default. If callers request more than the configured
limit, the store rejects the query instead of scanning or returning an
unbounded prompt/thread-history result set. Use `listPage` with an explicit
bounded `maxResults` and continuation cursor for large histories. When
`listPage` is called without `maxResults`, concrete stores use the configured
default as the effective page size, internally over-fetch one safe record to
detect continuation, and return a next query that carries the bounded page size.

Metadata is stored beside the content as a `.meta.properties` sidecar. Paths are
validated as relative paths and traversal such as `../` is rejected. Ambiguous
segments such as `.`, empty path segments, trailing slashes, and reserved
sidecar suffixes (`.lock`, `.tmp`, `.meta.properties`) are rejected so offline
stores cannot create hidden or colliding files. Local writes use per-document
`.lock` files plus atomic replace semantics so concurrent local agents do not
interleave prompt or thread-history updates. The file strategy does not follow
symbolic links when loading, listing, locking, or creating store directories, so
offline context cannot accidentally expose or write through files outside the
configured context root. Content and metadata reads are opened with no-follow
file handles, so external symlink swaps cannot redirect reads after validation.
Listing is deterministic by `scope` and `path`, matching the PostgreSQL strategy,
and cursor pagination resumes after the last returned `(scope, path)` pair.
Local listing sorts and bounds safe path candidates before reading document
content, so large offline thread/prompt directories do not require loading every
file just to return the next page.
`AgentContextStore.listPage` fetches one extra bounded result internally, trims
the returned document list back to `maxResults`, and only returns a continuation
cursor when another page is known to exist.
Invalid stray artifacts are skipped instead of failing the whole workspace scan.
If a local metadata sidecar is malformed or symlinked, the content file still
loads with default metadata; the next successful save or append rewrites the
sidecar inside the store. Stale `.tmp` files left by interrupted local writes are
removed opportunistically during workspace listing after they are older than the
active-writer grace window.

## API Example

```java
AgentContextKey key = new AgentContextKey(
        TenantId.of("tenant-1"),
        "wayang-platform",
        AgentContextScopes.THREAD,
        "threads/session-1.md");

store.append(key, "user: continue improvement\n", Map.of("agent", "codex"));

AgentContextQuery recentThreads = new AgentContextQuery(
        TenantId.of("tenant-1"),
        "wayang-platform",
        AgentContextScopes.THREAD,
        "threads/session-",
        50);

AgentContextQuery nextThreads = new AgentContextQuery(
        TenantId.of("tenant-1"),
        "wayang-platform",
        AgentContextScopes.THREAD,
        "threads/session-",
        50,
        new AgentContextCursor(AgentContextScopes.THREAD, "threads/session-1.md"));

AgentContextPage page = store.listPage(recentThreads).await().indefinitely();
Optional<AgentContextQuery> followingPage = page.nextQuery(recentThreads);
```

This keeps local agent history text-native while allowing the same engine code
to persist to PostgreSQL when deployed as a server/cloud runtime.
