CREATE TABLE IF NOT EXISTS workflow_run_wakeup_dead_letters (
    intent_id VARCHAR(64) PRIMARY KEY,
    wakeup_key VARCHAR(256) NOT NULL,
    run_id VARCHAR(64) NOT NULL,
    tenant_id VARCHAR(128),
    reason VARCHAR(128) NOT NULL,
    dead_letter_reason VARCHAR(128) NOT NULL,
    event_payload JSONB NOT NULL,
    attempts INTEGER NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    last_attempt_at TIMESTAMP WITH TIME ZONE,
    last_error TEXT,
    dead_lettered_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),

    CONSTRAINT ck_workflow_wakeup_dead_letter_attempts_positive
        CHECK (attempts > 0)
);

CREATE INDEX IF NOT EXISTS idx_workflow_wakeup_dead_letters_dead_lettered_at
    ON workflow_run_wakeup_dead_letters(dead_lettered_at DESC);
CREATE INDEX IF NOT EXISTS idx_workflow_wakeup_dead_letters_tenant_run
    ON workflow_run_wakeup_dead_letters(tenant_id, run_id);
CREATE INDEX IF NOT EXISTS idx_workflow_wakeup_dead_letters_reason
    ON workflow_run_wakeup_dead_letters(dead_letter_reason);

COMMENT ON TABLE workflow_run_wakeup_dead_letters
    IS 'Workflow run wake-up intents quarantined after exceeding delivery attempt limits';
COMMENT ON COLUMN workflow_run_wakeup_dead_letters.dead_letter_reason
    IS 'Stable reason code explaining why the wake-up was quarantined';
