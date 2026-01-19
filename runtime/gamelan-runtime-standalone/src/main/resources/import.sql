-- Sample data for the standalone runtime
-- This file is loaded when the database is created

-- Insert a default workflow definition if none exists
INSERT INTO workflow_definition (id, name, version, definition_json, created_at, updated_at, is_active) 
SELECT 'wf-001', 'Sample Workflow', '1.0.0', '{"nodes": [{"id": "start", "type": "start"}, {"id": "end", "type": "end"}], "edges": [{"from": "start", "to": "end"}]}', NOW(), NOW(), true
WHERE NOT EXISTS (SELECT 1 FROM workflow_definition WHERE id = 'wf-001');