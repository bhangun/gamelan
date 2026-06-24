CREATE TABLE IF NOT EXISTS workflow_recovery_leases (
    lease_name VARCHAR(255) PRIMARY KEY,
    owner_id VARCHAR(255) NOT NULL,
    acquired_at TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_workflow_recovery_leases_expires_at
    ON workflow_recovery_leases(expires_at);

COMMENT ON TABLE workflow_recovery_leases
    IS 'Distributed leases used to coordinate recovery sweep ownership across engine instances';
COMMENT ON COLUMN workflow_recovery_leases.lease_name
    IS 'Stable bounded lease key, for example workflow-recovery';
COMMENT ON COLUMN workflow_recovery_leases.owner_id
    IS 'Engine instance identifier that currently owns the lease';
