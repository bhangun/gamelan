CREATE TABLE IF NOT EXISTS workflow_processed_compensation_nodes (
    marker_id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    run_id VARCHAR(64) NOT NULL,
    tenant_id VARCHAR(64),
    node_id VARCHAR(128) NOT NULL,
    processed_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_processed_compensation_node_run
        FOREIGN KEY (run_id)
        REFERENCES workflow_runs(run_id) ON DELETE CASCADE
);

CREATE UNIQUE INDEX IF NOT EXISTS ux_processed_compensation_node_scope
    ON workflow_processed_compensation_nodes(COALESCE(tenant_id, ''), run_id, node_id);
CREATE INDEX IF NOT EXISTS idx_processed_compensation_node_run
    ON workflow_processed_compensation_nodes(run_id, node_id);
CREATE INDEX IF NOT EXISTS idx_processed_compensation_node_tenant
    ON workflow_processed_compensation_nodes(tenant_id, run_id);

COMMENT ON TABLE workflow_processed_compensation_nodes
    IS 'Durable idempotency markers for successful per-node compensation rollback progress';
