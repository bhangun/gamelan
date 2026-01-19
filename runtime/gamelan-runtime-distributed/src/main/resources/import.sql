-- This file allow to write SQL commands that will be emitted in test and dev.
-- The commands are commented as their support depends of the database
-- insert into myentity (id, field) values(1, 'field-1');
-- insert into myentity (id, field) values(2, 'field-2');
-- insert into myentity (id, field) values(3, 'field-3');
-- alter sequence myentity_seq restart with 4;

-- Workflow Definition Registry
CREATE TABLE IF NOT EXISTS workflow_definitions (
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
    UNIQUE (tenant_id, name, version)
);

-- Insert system tenant definition (example)
INSERT INTO workflow_definitions (
    definition_id, tenant_id, name, version, description, definition_json, created_by
) VALUES (
    'system-heartbeat', 'system', 'System Heartbeat', '1.0.0', 'System monitoring', '{"nodes": [], "inputs": {}, "outputs": {}}', 'system'
) ON CONFLICT DO NOTHING;