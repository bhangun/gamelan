CREATE TABLE IF NOT EXISTS task_dead_letters (
    message_id VARCHAR(256) PRIMARY KEY,
    run_id VARCHAR(64) NOT NULL,
    tenant_id VARCHAR(128),
    node_id VARCHAR(128) NOT NULL,
    reason VARCHAR(128) NOT NULL,
    delivery_attempt INTEGER NOT NULL DEFAULT 1,
    defer_count INTEGER NOT NULL DEFAULT 0,
    first_seen_at TIMESTAMP WITH TIME ZONE NOT NULL,
    dead_lettered_at TIMESTAMP WITH TIME ZONE NOT NULL,
    task_payload JSONB NOT NULL,
    diagnostics JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),

    CONSTRAINT ck_task_dead_letter_delivery_attempt_positive
        CHECK (delivery_attempt >= 1),
    CONSTRAINT ck_task_dead_letter_defer_count_nonnegative
        CHECK (defer_count >= 0)
);

CREATE INDEX IF NOT EXISTS idx_task_dead_letter_dead_lettered_at
    ON task_dead_letters(dead_lettered_at DESC);
CREATE INDEX IF NOT EXISTS idx_task_dead_letter_run
    ON task_dead_letters(run_id);
CREATE INDEX IF NOT EXISTS idx_task_dead_letter_node
    ON task_dead_letters(node_id);
CREATE INDEX IF NOT EXISTS idx_task_dead_letter_tenant_run
    ON task_dead_letters(tenant_id, run_id);
CREATE INDEX IF NOT EXISTS idx_task_dead_letter_reason
    ON task_dead_letters(reason);

COMMENT ON TABLE task_dead_letters
    IS 'Durable dead-letter storage for queued workflow node execution tasks';
COMMENT ON COLUMN task_dead_letters.message_id
    IS 'Original queue message id; used by operator replay/delete APIs';
COMMENT ON COLUMN task_dead_letters.task_payload
    IS 'Serialized NodeExecutionTask payload available for operator replay';
