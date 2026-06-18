ALTER TABLE config_override ADD COLUMN module VARCHAR(16) NOT NULL DEFAULT 'api';
ALTER TABLE config_override DROP CONSTRAINT config_override_pkey;
ALTER TABLE config_override ADD CONSTRAINT config_override_pkey PRIMARY KEY (module, key);

ALTER TABLE config_audit ADD COLUMN module VARCHAR(16) NOT NULL DEFAULT 'api';
DROP INDEX IF EXISTS idx_config_audit_key_changed_at;
CREATE INDEX idx_config_audit_module_key_changed_at ON config_audit(module, key, changed_at DESC);
