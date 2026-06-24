CREATE TABLE IF NOT EXISTS workflow_run_wakeup_outbox (
    wakeup_key VARCHAR(256) PRIMARY KEY,
    intent_id VARCHAR(64) NOT NULL UNIQUE,
    run_id VARCHAR(64) NOT NULL,
    tenant_id VARCHAR(128),
    reason VARCHAR(128) NOT NULL,
    event_payload JSONB NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    last_attempt_at TIMESTAMP WITH TIME ZONE,
    last_error TEXT,
    lease_owner VARCHAR(128),
    lease_expires_at TIMESTAMP WITH TIME ZONE,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),

    CONSTRAINT ck_workflow_wakeup_attempts_nonnegative
        CHECK (attempts >= 0)
);

CREATE INDEX IF NOT EXISTS idx_workflow_wakeup_created_at
    ON workflow_run_wakeup_outbox(created_at ASC);
CREATE INDEX IF NOT EXISTS idx_workflow_wakeup_tenant_run
    ON workflow_run_wakeup_outbox(tenant_id, run_id);
CREATE INDEX IF NOT EXISTS idx_workflow_wakeup_last_attempt
    ON workflow_run_wakeup_outbox(last_attempt_at);
CREATE INDEX IF NOT EXISTS idx_workflow_wakeup_lease
    ON workflow_run_wakeup_outbox(lease_expires_at, lease_owner);

COMMENT ON TABLE workflow_run_wakeup_outbox
    IS 'Durable level-triggered workflow run wake-up intents awaiting delivery to orchestration drivers';
COMMENT ON COLUMN workflow_run_wakeup_outbox.wakeup_key
    IS 'Coalescing key scoped by tenant and run id; one pending wake-up is enough to drive a run to convergence';
COMMENT ON COLUMN workflow_run_wakeup_outbox.intent_id
    IS 'Current delivery intent identity; replacement wake-ups get a new id so stale acknowledgements are ignored';
COMMENT ON COLUMN workflow_run_wakeup_outbox.lease_owner
    IS 'Engine instance currently claiming delivery of this wake-up intent';
COMMENT ON COLUMN workflow_run_wakeup_outbox.lease_expires_at
    IS 'Expiry timestamp after which another engine instance can reclaim delivery';
