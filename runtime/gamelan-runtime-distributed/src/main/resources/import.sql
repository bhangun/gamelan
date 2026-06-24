-- Optional seed data for development. Schema is managed by Flyway migrations.
INSERT INTO workflow_definitions (
    definition_id, tenant_id, name, version, description, definition_json, created_by
) VALUES (
    'system-heartbeat', 'system', 'System Heartbeat', '1.0.0', 'System monitoring', '{"nodes": [], "inputs": {}, "outputs": {}}', 'system'
) ON CONFLICT DO NOTHING;
