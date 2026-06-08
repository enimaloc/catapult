ALTER TABLE experiments
    ADD COLUMN rollout_percentage INT NOT NULL DEFAULT 100
        CONSTRAINT chk_experiments_rollout CHECK (rollout_percentage BETWEEN 0 AND 100);
