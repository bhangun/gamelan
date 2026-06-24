ALTER TABLE workflow_callbacks
    ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(64);

CREATE INDEX IF NOT EXISTS idx_callback_tenant_run
    ON workflow_callbacks(tenant_id, run_id);

COMMENT ON COLUMN workflow_callbacks.tenant_id
    IS 'Optional tenant scope for callback token validation; null rows are legacy pre-tenant callbacks';
