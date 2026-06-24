CREATE TABLE IF NOT EXISTS workflow_processed_node_results (
    marker_id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    run_id VARCHAR(64) NOT NULL,
    tenant_id VARCHAR(64),
    node_id VARCHAR(128) NOT NULL,
    attempt INTEGER NOT NULL,
    processed_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_processed_node_result_run
        FOREIGN KEY (run_id)
        REFERENCES workflow_runs(run_id) ON DELETE CASCADE
);

CREATE UNIQUE INDEX IF NOT EXISTS ux_processed_node_result_scope
    ON workflow_processed_node_results(COALESCE(tenant_id, ''), run_id, node_id, attempt);
CREATE INDEX IF NOT EXISTS idx_processed_node_result_run
    ON workflow_processed_node_results(run_id, node_id, attempt);
CREATE INDEX IF NOT EXISTS idx_processed_node_result_tenant
    ON workflow_processed_node_results(tenant_id, run_id);

CREATE TABLE IF NOT EXISTS workflow_processed_external_signals (
    marker_id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    run_id VARCHAR(64) NOT NULL,
    tenant_id VARCHAR(64),
    idempotency_key VARCHAR(128) NOT NULL,
    processed_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_processed_external_signal_run
        FOREIGN KEY (run_id)
        REFERENCES workflow_runs(run_id) ON DELETE CASCADE
);

CREATE UNIQUE INDEX IF NOT EXISTS ux_processed_external_signal_scope
    ON workflow_processed_external_signals(COALESCE(tenant_id, ''), run_id, idempotency_key);
CREATE INDEX IF NOT EXISTS idx_processed_external_signal_run
    ON workflow_processed_external_signals(run_id, idempotency_key);
CREATE INDEX IF NOT EXISTS idx_processed_external_signal_tenant
    ON workflow_processed_external_signals(tenant_id, run_id);

COMMENT ON TABLE workflow_processed_node_results
    IS 'Durable idempotency markers for executor node result deliveries';
COMMENT ON TABLE workflow_processed_external_signals
    IS 'Durable idempotency markers for external callback/signal deliveries';
