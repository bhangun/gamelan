# Gamelan DAG Module (Optional)

This module provides a DAG specialization for Gamelan workflows. It is **optional** and should only be enabled when `mode: DAG` is specified in workflow definitions.

## What It Adds

* DAG validation (cycle detection, orphan detection, single root)
* Topological scheduling helpers
* DAG execution policy hooks

## Integration

This module is intended to be loaded as a plugin through the Gamelan plugin system.

### Enable in Runtime

1. Build the `gamelan-dag` JAR
2. Drop it into the plugin directory (default: `./plugins`)
3. Set:
   - `gamelan.dag.plugin.enabled=true`
   - `gamelan.dag.scheduler.enabled=true` (optional topological ordering for ready nodes)
4. Use `mode: DAG` in workflow definitions

## Configuration

* `gamelan.dag.validator.enabled` (default: `true`)
* `gamelan.dag.validator.allowMultipleRoots` (default: `false`)
* `gamelan.dag.validator.allowOrphanNodes` (default: `false`)
* `gamelan.dag.validator.maxDepth` (default: `100`)
* `gamelan.dag.validator.maxWidth` (default: `50`)
