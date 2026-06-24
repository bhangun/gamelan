ALTER TABLE execution_tokens
    ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(64);

CREATE INDEX IF NOT EXISTS idx_token_tenant_run_node
    ON execution_tokens(tenant_id, run_id, node_id);

COMMENT ON COLUMN execution_tokens.tenant_id
    IS 'Optional tenant scope for execution token validation; null rows are legacy pre-tenant tokens';
