CREATE TABLE IF NOT EXISTS workflow_run_wakeup_dead_letter_audit (
    audit_id VARCHAR(64) PRIMARY KEY,
    operation VARCHAR(64) NOT NULL,
    outcome VARCHAR(64) NOT NULL,
    intent_id VARCHAR(64),
    query_payload JSONB,
    selected_count INTEGER NOT NULL,
    succeeded_count INTEGER NOT NULL,
    failed_count INTEGER NOT NULL,
    skipped_count INTEGER NOT NULL,
    dry_run BOOLEAN NOT NULL DEFAULT FALSE,
    intent_ids JSONB NOT NULL DEFAULT '[]'::jsonb,
    error TEXT,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),

    CONSTRAINT ck_workflow_wakeup_dead_letter_audit_counts_non_negative
        CHECK (
            selected_count >= 0
            AND succeeded_count >= 0
            AND failed_count >= 0
            AND skipped_count >= 0
        )
);

CREATE INDEX IF NOT EXISTS idx_workflow_wakeup_dead_letter_audit_occurred_at
    ON workflow_run_wakeup_dead_letter_audit(occurred_at DESC);
CREATE INDEX IF NOT EXISTS idx_workflow_wakeup_dead_letter_audit_operation
    ON workflow_run_wakeup_dead_letter_audit(operation, outcome);
CREATE INDEX IF NOT EXISTS idx_workflow_wakeup_dead_letter_audit_intent_id
    ON workflow_run_wakeup_dead_letter_audit(intent_id);

COMMENT ON TABLE workflow_run_wakeup_dead_letter_audit
    IS 'Operator audit log for workflow run wake-up dead-letter replay, delete, and purge actions';
