CREATE TABLE IF NOT EXISTS workflow_runs (
    run_id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    definition_id VARCHAR(128) NOT NULL,
    definition_version VARCHAR(32),
    status VARCHAR(32) NOT NULL,
    context_variables TEXT,
    node_executions TEXT,
    execution_path TEXT,
    created_at TIMESTAMP NOT NULL,
    started_at TIMESTAMP,
    completed_at TIMESTAMP,
    last_updated_at TIMESTAMP NOT NULL,
    version BIGINT,
    metadata TEXT
);

CREATE TABLE IF NOT EXISTS workflow_events (
    event_id VARCHAR(64) PRIMARY KEY,
    run_id VARCHAR(64) NOT NULL,
    tenant_id VARCHAR(64) NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    sequence_number BIGINT NOT NULL,
    event_data TEXT NOT NULL,
    occurred_at TIMESTAMP NOT NULL,
    metadata TEXT
);

CREATE TABLE IF NOT EXISTS workflow_processed_node_results (
    marker_id VARCHAR(64) PRIMARY KEY DEFAULT RANDOM_UUID(),
    run_id VARCHAR(64) NOT NULL,
    tenant_id VARCHAR(64),
    node_id VARCHAR(128) NOT NULL,
    attempt INTEGER NOT NULL,
    processed_at TIMESTAMP NOT NULL
);

CREATE TABLE IF NOT EXISTS workflow_processed_external_signals (
    marker_id VARCHAR(64) PRIMARY KEY DEFAULT RANDOM_UUID(),
    run_id VARCHAR(64) NOT NULL,
    tenant_id VARCHAR(64),
    idempotency_key VARCHAR(128) NOT NULL,
    processed_at TIMESTAMP NOT NULL
);

CREATE TABLE IF NOT EXISTS workflow_processed_compensation_nodes (
    marker_id VARCHAR(64) PRIMARY KEY DEFAULT RANDOM_UUID(),
    run_id VARCHAR(64) NOT NULL,
    tenant_id VARCHAR(64),
    node_id VARCHAR(128) NOT NULL,
    processed_at TIMESTAMP NOT NULL
);
