CREATE TABLE IF NOT EXISTS agent_context_documents (
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

CREATE INDEX IF NOT EXISTS idx_agent_context_workspace
    ON agent_context_documents(tenant_id, workspace_id);
CREATE INDEX IF NOT EXISTS idx_agent_context_scope
    ON agent_context_documents(tenant_id, workspace_id, scope);
CREATE INDEX IF NOT EXISTS idx_agent_context_updated_at
    ON agent_context_documents(updated_at DESC);
CREATE INDEX IF NOT EXISTS idx_agent_context_metadata
    ON agent_context_documents USING gin(metadata);

COMMENT ON TABLE agent_context_documents
    IS 'Agent context documents for local/cloud working memory and prompt/thread persistence';
