
-- ============================================================================
-- GAMELAN WORKFLOW ENGINE - DATABASE SCHEMA
-- ============================================================================
-- Migration: V1__initial_schema.sql
-- Description: Initial schema for workflow engine with event sourcing
-- Author: bhangun
-- Date: 2025-01-02

-- Enable required extensions
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS "pg_trgm";  -- For text search

-- ==================== WORKFLOW DEFINITIONS ====================

-- Workflow Definition Registry
CREATE TABLE workflow_definitions (
    definition_id VARCHAR(128) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    name VARCHAR(255) NOT NULL,
    version VARCHAR(32) NOT NULL,
    description TEXT,
    definition_json JSONB NOT NULL,
    is_active BOOLEAN DEFAULT true,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    created_by VARCHAR(128),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_by VARCHAR(128),
    metadata JSONB,
    
    CONSTRAINT uk_workflow_def_tenant_name_version 
        UNIQUE (tenant_id, name, version)
);

CREATE INDEX idx_workflow_def_tenant ON workflow_definitions(tenant_id);
CREATE INDEX idx_workflow_def_active ON workflow_definitions(is_active);
CREATE INDEX idx_workflow_def_name ON workflow_definitions(name);
CREATE INDEX idx_workflow_def_metadata ON workflow_definitions USING gin(metadata);

-- ==================== WORKFLOW RUNS (Snapshot Store) ====================

-- Workflow Run Snapshots - Materialized view for fast querying
CREATE TABLE workflow_runs (
    run_id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    definition_id VARCHAR(128) NOT NULL,
    definition_version VARCHAR(32),
    status VARCHAR(32) NOT NULL,
    
    -- Context and state
    context_variables JSONB,
    node_executions JSONB,
    execution_path JSONB,
    suspension_info JSONB,
    pending_signals JSONB,
    compensation_state JSONB,
    
    -- Temporal tracking
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    started_at TIMESTAMP WITH TIME ZONE,
    completed_at TIMESTAMP WITH TIME ZONE,
    last_updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    
    -- Optimistic locking
    version BIGINT NOT NULL DEFAULT 0,
    
    -- Metadata and tags
    metadata JSONB,
    labels JSONB,
    
    -- Parent-child relationships (for sub-workflows)
    parent_run_id VARCHAR(64),
    
    CONSTRAINT fk_workflow_run_definition 
        FOREIGN KEY (definition_id) 
        REFERENCES workflow_definitions(definition_id),
    
    CONSTRAINT fk_workflow_run_parent
        FOREIGN KEY (parent_run_id)
        REFERENCES workflow_runs(run_id) ON DELETE SET NULL
);

-- Indexes for efficient querying
CREATE INDEX idx_workflow_run_tenant_status ON workflow_runs(tenant_id, status);
CREATE INDEX idx_workflow_run_definition ON workflow_runs(definition_id);
CREATE INDEX idx_workflow_run_created_at ON workflow_runs(created_at DESC);
CREATE INDEX idx_workflow_run_status ON workflow_runs(status);
CREATE INDEX idx_workflow_run_parent ON workflow_runs(parent_run_id);
CREATE INDEX idx_workflow_run_labels ON workflow_runs USING gin(labels);
CREATE INDEX idx_workflow_run_metadata ON workflow_runs USING gin(metadata);

-- Partitioning by tenant (optional, for very large deployments)
-- CREATE TABLE workflow_runs_partition_template (LIKE workflow_runs INCLUDING ALL);

-- ==================== EVENT STORE ====================

-- Immutable event log - source of truth
CREATE TABLE workflow_events (
    event_id VARCHAR(64) PRIMARY KEY,
    run_id VARCHAR(64) NOT NULL,
    tenant_id VARCHAR(64) NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    sequence_number BIGINT NOT NULL,
    
    -- Event payload
    event_data JSONB NOT NULL,
    
    -- Metadata
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
    metadata JSONB,
    
    -- Correlation for distributed tracing
    correlation_id VARCHAR(64),
    causation_id VARCHAR(64),
    
    CONSTRAINT uk_event_tenant_run_sequence
        UNIQUE (tenant_id, run_id, sequence_number)
);

-- Indexes for event sourcing queries
CREATE INDEX idx_event_run_id ON workflow_events(tenant_id, run_id, sequence_number);
CREATE INDEX idx_event_run_lookup ON workflow_events(run_id, tenant_id, sequence_number);
CREATE INDEX idx_event_type ON workflow_events(event_type);
CREATE INDEX idx_event_occurred_at ON workflow_events(occurred_at DESC);
CREATE INDEX idx_event_tenant ON workflow_events(tenant_id);
CREATE INDEX idx_event_correlation ON workflow_events(correlation_id);

-- Partition by time for event archival
-- CREATE TABLE workflow_events_2025_01 PARTITION OF workflow_events
--     FOR VALUES FROM ('2025-01-01') TO ('2025-02-01');

-- Durable idempotency markers for executor result and external callback/signal ingestion.
-- Null tenant rows are legacy/global markers; tenant rows are isolated per tenant.
CREATE TABLE workflow_processed_node_results (
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

CREATE UNIQUE INDEX ux_processed_node_result_scope
    ON workflow_processed_node_results(COALESCE(tenant_id, ''), run_id, node_id, attempt);
CREATE INDEX idx_processed_node_result_run
    ON workflow_processed_node_results(run_id, node_id, attempt);
CREATE INDEX idx_processed_node_result_tenant
    ON workflow_processed_node_results(tenant_id, run_id);

CREATE TABLE workflow_processed_external_signals (
    marker_id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    run_id VARCHAR(64) NOT NULL,
    tenant_id VARCHAR(64),
    idempotency_key VARCHAR(128) NOT NULL,
    processed_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_processed_external_signal_run
        FOREIGN KEY (run_id)
        REFERENCES workflow_runs(run_id) ON DELETE CASCADE
);

CREATE UNIQUE INDEX ux_processed_external_signal_scope
    ON workflow_processed_external_signals(COALESCE(tenant_id, ''), run_id, idempotency_key);
CREATE INDEX idx_processed_external_signal_run
    ON workflow_processed_external_signals(run_id, idempotency_key);
CREATE INDEX idx_processed_external_signal_tenant
    ON workflow_processed_external_signals(tenant_id, run_id);

-- ==================== AGENT CONTEXT DOCUMENTS ====================

-- Persistent local/cloud agent working memory such as AGENTS.md, SKILLS.md,
-- prompt history, thread notes, and tool-generated context documents.
CREATE TABLE agent_context_documents (
    tenant_id VARCHAR(128) NOT NULL,
    workspace_id VARCHAR(256) NOT NULL,
    scope VARCHAR(64) NOT NULL,
    document_path VARCHAR(1024) NOT NULL,
    content TEXT NOT NULL,
    content_type VARCHAR(128) NOT NULL DEFAULT 'text/markdown',
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_agent_context_documents
        PRIMARY KEY (tenant_id, workspace_id, scope, document_path)
);

CREATE INDEX idx_agent_context_workspace ON agent_context_documents(tenant_id, workspace_id);
CREATE INDEX idx_agent_context_scope ON agent_context_documents(tenant_id, workspace_id, scope);
CREATE INDEX idx_agent_context_updated_at ON agent_context_documents(updated_at DESC);
CREATE INDEX idx_agent_context_metadata ON agent_context_documents USING gin(metadata);

-- ==================== EXECUTION TOKENS ====================

-- Security tokens for node execution
CREATE TABLE execution_tokens (
    token_hash VARCHAR(128) PRIMARY KEY,
    run_id VARCHAR(64) NOT NULL,
    tenant_id VARCHAR(64),
    node_id VARCHAR(128) NOT NULL,
    attempt INTEGER NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    
    -- Execution context
    executor_id VARCHAR(128),
    issued_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    
    CONSTRAINT fk_token_run 
        FOREIGN KEY (run_id) 
        REFERENCES workflow_runs(run_id) ON DELETE CASCADE
);

CREATE INDEX idx_token_run_node ON execution_tokens(run_id, node_id);
CREATE INDEX idx_token_tenant_run_node ON execution_tokens(tenant_id, run_id, node_id);
CREATE INDEX idx_token_expires ON execution_tokens(expires_at);

-- ==================== CALLBACKS ====================

-- External callback registrations
CREATE TABLE workflow_callbacks (
    callback_id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    callback_token_hash VARCHAR(128) UNIQUE NOT NULL,
    run_id VARCHAR(64) NOT NULL,
    tenant_id VARCHAR(64),
    node_id VARCHAR(128) NOT NULL,
    callback_url TEXT NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    
    -- Callback status
    status VARCHAR(32) DEFAULT 'PENDING',
    invoked_at TIMESTAMP WITH TIME ZONE,
    response_data JSONB,
    
    -- Security
    hmac_signature VARCHAR(256),
    
    CONSTRAINT fk_callback_run 
        FOREIGN KEY (run_id) 
        REFERENCES workflow_runs(run_id) ON DELETE CASCADE
);

CREATE INDEX idx_callback_run ON workflow_callbacks(run_id);
CREATE INDEX idx_callback_tenant_run ON workflow_callbacks(tenant_id, run_id);
CREATE INDEX idx_callback_token_hash ON workflow_callbacks(callback_token_hash);
CREATE INDEX idx_callback_expires ON workflow_callbacks(expires_at);

-- ==================== TASK QUEUE ====================

-- Distributed task queue (complementing Redis)
CREATE TABLE task_queue (
    task_id VARCHAR(128) PRIMARY KEY,
    run_id VARCHAR(64) NOT NULL,
    node_id VARCHAR(128) NOT NULL,
    attempt INTEGER NOT NULL,
    
    -- Task details
    executor_type VARCHAR(64) NOT NULL,
    task_payload JSONB NOT NULL,
    
    -- Scheduling
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    scheduled_at TIMESTAMP WITH TIME ZONE NOT NULL,
    started_at TIMESTAMP WITH TIME ZONE,
    completed_at TIMESTAMP WITH TIME ZONE,
    
    -- Retry tracking
    retry_count INTEGER DEFAULT 0,
    next_retry_at TIMESTAMP WITH TIME ZONE,
    
    -- Assignment
    assigned_executor VARCHAR(128),
    lease_expires_at TIMESTAMP WITH TIME ZONE,
    
    CONSTRAINT fk_task_run 
        FOREIGN KEY (run_id) 
        REFERENCES workflow_runs(run_id) ON DELETE CASCADE
);

CREATE INDEX idx_task_status ON task_queue(status);
CREATE INDEX idx_task_scheduled ON task_queue(scheduled_at);
CREATE INDEX idx_task_run ON task_queue(run_id);
CREATE INDEX idx_task_executor ON task_queue(executor_type);
CREATE INDEX idx_task_lease ON task_queue(lease_expires_at) 
    WHERE status = 'RUNNING';

-- ==================== EXECUTORS ====================

-- Executor registry for service discovery
CREATE TABLE executors (
    executor_id VARCHAR(128) PRIMARY KEY,
    executor_type VARCHAR(64) NOT NULL,
    communication_type VARCHAR(32) NOT NULL,
    endpoint TEXT NOT NULL,
    
    -- Health tracking
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    last_heartbeat TIMESTAMP WITH TIME ZONE,
    
    -- Capabilities
    supported_node_types JSONB,
    max_concurrent_tasks INTEGER DEFAULT 10,
    current_task_count INTEGER DEFAULT 0,
    
    -- Metadata
    version VARCHAR(32),
    metadata JSONB,
    
    registered_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_executor_type ON executors(executor_type);
CREATE INDEX idx_executor_status ON executors(status);
CREATE INDEX idx_executor_heartbeat ON executors(last_heartbeat);

-- ==================== AUDIT LOG ====================

-- Audit trail for compliance
CREATE TABLE audit_log (
    audit_id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    tenant_id VARCHAR(64) NOT NULL,
    entity_type VARCHAR(64) NOT NULL,
    entity_id VARCHAR(128) NOT NULL,
    action VARCHAR(64) NOT NULL,
    
    -- Change tracking
    old_value JSONB,
    new_value JSONB,
    
    -- Context
    performed_by VARCHAR(128),
    performed_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    ip_address INET,
    user_agent TEXT,
    
    -- Additional context
    metadata JSONB
);

CREATE INDEX idx_audit_tenant ON audit_log(tenant_id);
CREATE INDEX idx_audit_entity ON audit_log(entity_type, entity_id);
CREATE INDEX idx_audit_performed_at ON audit_log(performed_at DESC);
CREATE INDEX idx_audit_performed_by ON audit_log(performed_by);

-- Partition by month for efficient archival
-- CREATE TABLE audit_log_2025_01 PARTITION OF audit_log
--     FOR VALUES FROM ('2025-01-01') TO ('2025-02-01');

-- ==================== METRICS & MONITORING ====================

-- Workflow execution metrics (aggregated)
CREATE TABLE workflow_metrics (
    metric_id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    tenant_id VARCHAR(64) NOT NULL,
    definition_id VARCHAR(128) NOT NULL,
    metric_type VARCHAR(64) NOT NULL,
    
    -- Time window
    window_start TIMESTAMP WITH TIME ZONE NOT NULL,
    window_end TIMESTAMP WITH TIME ZONE NOT NULL,
    
    -- Metrics
    execution_count BIGINT DEFAULT 0,
    success_count BIGINT DEFAULT 0,
    failure_count BIGINT DEFAULT 0,
    
    -- Performance
    avg_duration_ms BIGINT,
    min_duration_ms BIGINT,
    max_duration_ms BIGINT,
    p50_duration_ms BIGINT,
    p95_duration_ms BIGINT,
    p99_duration_ms BIGINT,
    
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    
    CONSTRAINT uk_metric_window 
        UNIQUE (tenant_id, definition_id, metric_type, window_start)
);

CREATE INDEX idx_metric_tenant_def ON workflow_metrics(tenant_id, definition_id);
CREATE INDEX idx_metric_window ON workflow_metrics(window_start, window_end);

-- ==================== VIEWS ====================

-- Active workflows view
CREATE VIEW v_active_workflows AS
SELECT 
    wr.run_id,
    wr.tenant_id,
    wd.name as workflow_name,
    wr.status,
    wr.created_at,
    wr.started_at,
    EXTRACT(EPOCH FROM (NOW() - wr.started_at)) as running_duration_seconds,
    (
        SELECT COUNT(*)
        FROM jsonb_each(COALESCE(wr.node_executions, '{}'::jsonb))
            AS node_entries(node_id, node_data)
    ) as total_nodes,
    (
        SELECT COUNT(*)
        FROM jsonb_each(COALESCE(wr.node_executions, '{}'::jsonb))
            AS node_entries(node_id, node_data)
        WHERE node_entries.node_data->>'status' = 'COMPLETED'
    ) as completed_nodes
FROM workflow_runs wr
JOIN workflow_definitions wd ON wr.definition_id = wd.definition_id
WHERE wr.status IN ('PENDING', 'RUNNING', 'SUSPENDED', 'COMPENSATING');

-- Workflow statistics view
CREATE VIEW v_workflow_statistics AS
SELECT 
    wr.tenant_id,
    wd.name as workflow_name,
    wd.version,
    COUNT(*) as total_executions,
    COUNT(*) FILTER (WHERE wr.status = 'COMPLETED') as successful_executions,
    COUNT(*) FILTER (WHERE wr.status = 'FAILED') as failed_executions,
    COUNT(*) FILTER (WHERE wr.status IN ('PENDING', 'RUNNING', 'SUSPENDED', 'COMPENSATING')) as active_executions,
    AVG(EXTRACT(EPOCH FROM (wr.completed_at - wr.started_at))) as avg_duration_seconds,
    MIN(wr.created_at) as first_execution,
    MAX(wr.created_at) as last_execution
FROM workflow_runs wr
JOIN workflow_definitions wd ON wr.definition_id = wd.definition_id
GROUP BY wr.tenant_id, wd.name, wd.version;

-- ==================== FUNCTIONS ====================

-- Function to clean up expired tokens
CREATE OR REPLACE FUNCTION cleanup_expired_tokens()
RETURNS INTEGER AS $$
DECLARE
    deleted_tokens INTEGER;
    deleted_callbacks INTEGER;
BEGIN
    DELETE FROM execution_tokens 
    WHERE expires_at < NOW() - INTERVAL '1 hour';
    
    GET DIAGNOSTICS deleted_tokens = ROW_COUNT;

    DELETE FROM workflow_callbacks
    WHERE expires_at < NOW() - INTERVAL '1 hour';

    GET DIAGNOSTICS deleted_callbacks = ROW_COUNT;
    RETURN deleted_tokens + deleted_callbacks;
END;
$$ LANGUAGE plpgsql;

-- Function to update workflow run snapshot
CREATE OR REPLACE FUNCTION update_workflow_run_snapshot()
RETURNS TRIGGER AS $$
BEGIN
    NEW.last_updated_at = NOW();
    NEW.version = OLD.version + 1;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- Trigger for automatic snapshot updates
CREATE TRIGGER trg_workflow_run_update
    BEFORE UPDATE ON workflow_runs
    FOR EACH ROW
    EXECUTE FUNCTION update_workflow_run_snapshot();

-- Function to get workflow run history
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

-- ==================== PERFORMANCE OPTIMIZATIONS ====================

-- Analyze tables for query planning
ANALYZE workflow_definitions;
ANALYZE workflow_runs;
ANALYZE workflow_events;
ANALYZE agent_context_documents;

-- Set statistics targets for important columns
ALTER TABLE workflow_runs ALTER COLUMN tenant_id SET STATISTICS 1000;
ALTER TABLE workflow_runs ALTER COLUMN status SET STATISTICS 1000;
ALTER TABLE workflow_events ALTER COLUMN run_id SET STATISTICS 1000;

-- ==================== COMMENTS ====================

COMMENT ON TABLE workflow_definitions IS 'Workflow definition registry - blueprints for workflow execution';
COMMENT ON TABLE workflow_runs IS 'Workflow run snapshots - materialized view for fast querying (CQRS read model)';
COMMENT ON TABLE workflow_events IS 'Immutable event log - source of truth for event sourcing';
COMMENT ON TABLE agent_context_documents IS 'Agent context documents for local/cloud working memory and prompt/thread persistence';
COMMENT ON TABLE execution_tokens IS 'Security tokens for authorized node execution';
COMMENT ON COLUMN execution_tokens.token_hash IS 'SHA-256 hash of the bearer execution token; raw token values are not stored';
COMMENT ON COLUMN execution_tokens.tenant_id IS 'Optional tenant scope for execution token validation; null rows are legacy pre-tenant tokens';
COMMENT ON TABLE workflow_callbacks IS 'External callback registrations for async operations';
COMMENT ON COLUMN workflow_callbacks.tenant_id IS 'Optional tenant scope for callback token validation; null rows are legacy pre-tenant callbacks';
COMMENT ON COLUMN workflow_callbacks.callback_token_hash IS 'SHA-256 hash of the bearer callback token; raw token values are not stored';
COMMENT ON TABLE task_queue IS 'Distributed task queue for node execution scheduling';
COMMENT ON TABLE executors IS 'Executor registry for service discovery and load balancing';
COMMENT ON TABLE audit_log IS 'Audit trail for compliance and security';
COMMENT ON TABLE workflow_metrics IS 'Aggregated metrics for monitoring and analytics';

-- ==================== INITIAL DATA ====================

-- Insert system tenant
INSERT INTO workflow_definitions (
    definition_id, 
    tenant_id, 
    name, 
    version, 
    description,
    definition_json,
    created_by
) VALUES (
    'system-heartbeat',
    'system',
    'System Heartbeat',
    '1.0.0',
    'Internal workflow for system health monitoring',
    '{"nodes": [], "inputs": {}, "outputs": {}}',
    'system'
) ON CONFLICT DO NOTHING;

-- Grant appropriate permissions (adjust based on your security model)
-- GRANT SELECT, INSERT, UPDATE ON ALL TABLES IN SCHEMA public TO workflow_service;
-- GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO workflow_service;
