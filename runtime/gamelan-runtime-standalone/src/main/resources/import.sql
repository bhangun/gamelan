-- Optional seed data for standalone database-backed profiles.
-- Default standalone uses the in-memory definition repository and disables SQL load scripts.
INSERT INTO workflow_definitions (
    definition_id, tenant_id, name, version, description, definition_json, created_by
) VALUES (
    'wf-001',
    'default-tenant',
    'Sample Workflow',
    '1.0.0',
    'Sample standalone workflow',
    '{"nodes": [{"id": "start", "type": "start"}, {"id": "end", "type": "end"}], "edges": [{"from": "start", "to": "end"}]}',
    'system'
) ON CONFLICT DO NOTHING;
