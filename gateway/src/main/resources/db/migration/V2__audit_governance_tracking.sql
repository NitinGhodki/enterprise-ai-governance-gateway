-- Add the audit log columns identified in Day 3 Q3
-- Run as a new migration: V2__audit_governance_tracking.sql

ALTER TABLE audit_events
    ADD COLUMN IF NOT EXISTS governance_available  BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN IF NOT EXISTS quality_check_source  VARCHAR(20) NOT NULL DEFAULT 'VERIFIED';

-- Index for budget queries — monthly aggregation per user
CREATE INDEX IF NOT EXISTS idx_audit_user_month
    ON audit_events(user_id, created_at);
--    WHERE created_at >= DATE_TRUNC('month', NOW());