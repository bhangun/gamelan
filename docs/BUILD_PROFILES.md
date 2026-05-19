# Gamelan Build Profiles

Gamelan uses a small base reactor plus Maven profiles to keep builds focused.
Running Maven without a profile keeps the historical full build through the
default `all` profile.

## Profiles

| Profile | Modules Added |
| --- | --- |
| `core` | Minimal engine SPI, plugin SPI, engine core, SDK core/local modules |
| `server` | Quarkus engine app and executor registry |
| `protocols` | gRPC and Kafka protocol modules |
| `runtimes` | Standalone and distributed runtime modules |
| `executor` | Executor registry and executor runtime modules |
| `client` | Remote client SDK modules |
| `plugins` | Built-in plugin modules |
| `extensions` | Optional extension modules, currently `gamelan-dag` |
| `examples` | Example clients, executors, and plugin demos |
| `all` | Full historical reactor, active by default |

## Examples

Build only the engine and SDK:

```bash
mvn -Pcore test
```

Build core plus runtimes:

```bash
mvn -Pcore,runtimes test
```

Build server-side runtime pieces:

```bash
mvn -Pcore,server test
```

Build optional extensions:

```bash
mvn -Pextensions test
```

Run the historical full build:

```bash
mvn test
```

## Design Rule

The engine core should compile without domain-specific integrations. Agentic AI,
EIP, storage, model providers, business-system connectors, and transport-specific
behavior should enter through SPI contracts, plugins, or optional modules. This
keeps Gamelan domain-agnostic while still allowing first-class workflow support
for each domain.
