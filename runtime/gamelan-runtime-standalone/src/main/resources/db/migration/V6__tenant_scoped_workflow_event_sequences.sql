-- Tenant-scope workflow event sequence numbers so colliding run ids in
-- different tenants do not serialize together or conflict in the event log.

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'uk_event_run_sequence'
        AND conrelid = 'workflow_events'::regclass
    ) THEN
        ALTER TABLE workflow_events DROP CONSTRAINT uk_event_run_sequence;
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'uk_event_tenant_run_sequence'
        AND conrelid = 'workflow_events'::regclass
    ) THEN
        ALTER TABLE workflow_events
            ADD CONSTRAINT uk_event_tenant_run_sequence
            UNIQUE (tenant_id, run_id, sequence_number);
    END IF;
END $$;

DROP INDEX IF EXISTS idx_event_run_id;

CREATE INDEX IF NOT EXISTS idx_event_run_id
    ON workflow_events(tenant_id, run_id, sequence_number);

CREATE INDEX IF NOT EXISTS idx_event_run_lookup
    ON workflow_events(run_id, tenant_id, sequence_number);

COMMENT ON CONSTRAINT uk_event_tenant_run_sequence ON workflow_events
    IS 'Workflow event sequence numbers are isolated by tenant and run id';

CREATE OR REPLACE FUNCTION get_workflow_run_history(p_run_id VARCHAR)
RETURNS TABLE (
    event_id VARCHAR,
    event_type VARCHAR,
    sequence_number BIGINT,
    occurred_at TIMESTAMP WITH TIME ZONE,
    event_data JSONB
) AS $$
BEGIN
    RETURN QUERY
    SELECT
        we.event_id,
        we.event_type,
        we.sequence_number,
        we.occurred_at,
        we.event_data
    FROM workflow_events we
    WHERE we.run_id = p_run_id
    AND we.tenant_id = 'system'
    ORDER BY we.sequence_number;
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION get_workflow_run_history(p_run_id VARCHAR, p_tenant_id VARCHAR)
RETURNS TABLE (
    event_id VARCHAR,
    event_type VARCHAR,
    sequence_number BIGINT,
    occurred_at TIMESTAMP WITH TIME ZONE,
    event_data JSONB
) AS $$
BEGIN
    RETURN QUERY
    SELECT
        we.event_id,
        we.event_type,
        we.sequence_number,
        we.occurred_at,
        we.event_data
    FROM workflow_events we
    WHERE we.run_id = p_run_id
    AND (we.tenant_id = p_tenant_id OR we.tenant_id = 'system')
    ORDER BY CASE WHEN we.tenant_id = 'system' THEN 0 ELSE 1 END, we.sequence_number;
END;
$$ LANGUAGE plpgsql;
