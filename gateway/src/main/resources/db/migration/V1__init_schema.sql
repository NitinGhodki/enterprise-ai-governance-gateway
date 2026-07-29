-- Users table — stores API consumers
CREATE TABLE IF NOT EXISTS users (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email       VARCHAR(255) NOT NULL UNIQUE,
    password    VARCHAR(255) NOT NULL,      -- bcrypt hash
    api_key     VARCHAR(64)  NOT NULL UNIQUE,
    role        VARCHAR(50)  NOT NULL DEFAULT 'USER',
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    is_active   BOOLEAN      NOT NULL DEFAULT TRUE
);

-- Audit log — every LLM request through the gateway
CREATE TABLE IF NOT EXISTS audit_events (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id             UUID NOT NULL REFERENCES users(id),
    request_id          VARCHAR(36) NOT NULL UNIQUE,
    provider            VARCHAR(50) NOT NULL,
    model               VARCHAR(100) NOT NULL,
    prompt_tokens       INTEGER,
    completion_tokens   INTEGER,
    total_tokens        INTEGER,
    estimated_cost_usd  NUMERIC(10, 8),
    latency_ms          INTEGER,
    cache_hit           BOOLEAN NOT NULL DEFAULT FALSE,
    governance_passed   BOOLEAN NOT NULL DEFAULT TRUE,
    governance_score    NUMERIC(5, 4),
    safety_flags        TEXT[],             -- array of triggered safety rules
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Cost budget table — per-user monthly spend limits
CREATE TABLE IF NOT EXISTS user_budgets (
    user_id             UUID PRIMARY KEY REFERENCES users(id),
    monthly_limit_usd   NUMERIC(10, 2) NOT NULL DEFAULT 10.00,
    current_month_usd   NUMERIC(10, 8) NOT NULL DEFAULT 0.00,
    budget_month        DATE NOT NULL DEFAULT DATE_TRUNC('month', NOW()),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Indexes for common query patterns
CREATE INDEX IF NOT EXISTS idx_audit_user_id
    ON audit_events(user_id);
CREATE INDEX IF NOT EXISTS idx_audit_created_at
    ON audit_events(created_at DESC);
CREATE INDEX IF NOT EXISTS idx_audit_user_created
    ON audit_events(user_id, created_at DESC);

-- Seed a default admin user (password: admin123 bcrypt hash)
-- Change this in production
INSERT INTO users (email, password, api_key, role)
VALUES (
    'admin@aigovernance.local',
    '$2a$12$n9oQ8IZru87GWVDy.EqSDe/MT/WmJO.VgPRKyz1cr0dTN8KCN3J/G',
    'gw-admin-key-change-in-production-00000001',
    'ADMIN'
) ON CONFLICT (email) DO NOTHING;