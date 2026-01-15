-- Create executors table
CREATE TABLE IF NOT EXISTS executors (
    executor_id VARCHAR(255) PRIMARY KEY NOT NULL,
    executor_type VARCHAR(255) NOT NULL,
    communication_type VARCHAR(50) NOT NULL,
    endpoint VARCHAR(500) NOT NULL,
    timeout_seconds BIGINT,
    metadata JSONB DEFAULT '{}'
);

-- Create indexes for better query performance
CREATE INDEX IF NOT EXISTS idx_executor_type ON executors(executor_type);
CREATE INDEX IF NOT EXISTS idx_communication_type ON executors(communication_type);