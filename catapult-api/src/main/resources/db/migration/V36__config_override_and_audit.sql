CREATE TABLE config_override (
    key VARCHAR(255) PRIMARY KEY,
    value TEXT,
    is_secret BOOLEAN NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    updated_by UUID NOT NULL REFERENCES user_account(id)
);

CREATE TABLE config_audit (
    id UUID PRIMARY KEY,
    key VARCHAR(255) NOT NULL,
    previous_value TEXT,
    new_value TEXT,
    changed_at TIMESTAMP NOT NULL,
    changed_by UUID NOT NULL REFERENCES user_account(id)
);

CREATE INDEX idx_config_audit_key_changed_at ON config_audit(key, changed_at DESC);
